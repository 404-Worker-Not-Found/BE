package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobPaymentChangeRequest;
import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;
import com.workernotfound.job.domain.job.entity.JobPaymentTerms;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.PaymentChangeType;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.domain.job.repository.JobApplicationAdmissionRepository;
import com.workernotfound.job.domain.job.repository.JobMatchingSeatReservationRepository;
import com.workernotfound.job.domain.job.repository.JobPaymentChangeRequestRepository;
import com.workernotfound.job.domain.job.repository.JobPaymentFundingRepository;
import com.workernotfound.job.domain.job.repository.JobPaymentOrderCommandRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.global.exception.BusinessException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 점주의 결제 조건 변경과 재결제 요청을 받아 새 주문 생성 명령을 발급한다.
 *
 * <p>공고 행 잠금 아래에서 요청 행과 다음 순번의 명령을 함께 저장할 뿐, 공고의 현재 조건과 주문 연결은 바꾸지 않는다. 새 주문이 검증·연결되는
 * 트랜잭션({@link PaymentOrderCommandTransactionService#recordCreated})에서만 조건이 적용된다. 교체 가능 여부의 최종 판단은
 * payment-service가 자신의 잠금 안에서 한다. 여기서는 job-service가 알 수 있는 거절 사유만 먼저 거절하며, 이 확인을 통과했다고 교체가
 * 확정되지는 않는다.
 */
@Service
@RequiredArgsConstructor
public class JobPaymentChangeCommandService {

    private final JobPostRepository jobPostRepository;
    private final JobPaymentChangeRequestRepository changeRequestRepository;
    private final JobPaymentOrderCommandRepository commandRepository;
    private final JobPaymentFundingRepository fundingRepository;
    private final JobApplicationAdmissionRepository admissionRepository;
    private final JobMatchingSeatReservationRepository reservationRepository;
    private final JobPaymentOrderCommandIssuer commandIssuer;
    private final JobWageCalculator wageCalculator;
    private final Clock clock;

    @Transactional
    public JobPaymentChange changeTerms(
            Long jobPostId,
            Long ownerMemberId,
            JobPaymentTerms terms,
            String idempotencyKey
    ) {
        JobPost jobPost = lockOwnedJob(jobPostId, ownerMemberId);
        Optional<JobPaymentChange> replay =
                findReplay(jobPostId, ownerMemberId, PaymentChangeType.TERMS_CHANGE, terms, idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        long amount = calculateTotalDeposit(terms);
        validateApplicationDeadline(terms, now);
        requireReplaceable(jobPost);
        if (jobPost.paymentTerms().equals(terms)) {
            throw new BusinessException(JobErrorCode.PAYMENT_TERMS_UNCHANGED);
        }
        return issue(jobPost, idempotencyKey, PaymentChangeType.TERMS_CHANGE, terms, amount,
                nextPaymentJobVersion(jobPost), now);
    }

    @Transactional
    public JobPaymentChange retryPayment(Long jobPostId, Long ownerMemberId, String idempotencyKey) {
        JobPost jobPost = lockOwnedJob(jobPostId, ownerMemberId);
        Optional<JobPaymentChange> replay =
                findReplay(jobPostId, ownerMemberId, PaymentChangeType.REPAYMENT, null, idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        requireReplaceable(jobPost);
        if (!jobPost.getApplicationDeadline().isAfter(now)) {
            // 예치돼도 공개할 수 없어 환불 검토 대상만 남는다. 일정을 바꾸려면 결제 조건 변경을 쓴다.
            throw new BusinessException(JobErrorCode.PAYMENT_CHANGE_NOT_ALLOWED,
                    "지원 마감이 지난 공고는 같은 조건으로 재결제할 수 없습니다. 결제 조건 변경으로 일정을 바꾸세요.");
        }
        // 같은 조건이면 결제용 버전과 금액을 유지한다. payment-service는 같은 버전의 교체를 같은 금액의 FAILED 주문에만 허용한다.
        return issue(jobPost, idempotencyKey, PaymentChangeType.REPAYMENT, jobPost.paymentTerms(),
                jobPost.getPaymentAmount(), jobPost.getPaymentJobVersion(), now);
    }

    // 다른 회원의 공고는 존재 여부를 드러내지 않도록 없는 공고와 같은 404로 응답한다.
    private JobPost lockOwnedJob(Long jobPostId, Long ownerMemberId) {
        JobPost jobPost = jobPostRepository.findByIdForUpdate(jobPostId)
                .orElseThrow(() -> new BusinessException(JobErrorCode.JOB_NOT_FOUND));
        if (!jobPost.getOwnerId().equals(ownerMemberId)) {
            throw new BusinessException(JobErrorCode.JOB_NOT_FOUND);
        }
        return jobPost;
    }

    // 업무 상태보다 먼저 확인한다. 같은 키·같은 요청은 그 요청의 현재 처리 상태를 돌려준다(진행 중·적용·거절).
    private Optional<JobPaymentChange> findReplay(
            Long jobPostId,
            Long ownerMemberId,
            PaymentChangeType changeType,
            JobPaymentTerms terms,
            String idempotencyKey
    ) {
        return changeRequestRepository.findByIdempotencyKey(idempotencyKey).map(request -> {
            if (!request.isSameRequest(jobPostId, ownerMemberId, changeType, terms)) {
                throw new BusinessException(JobErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            JobPaymentOrderCommand command = commandRepository.findById(request.getCommandId()).orElseThrow();
            return new JobPaymentChange(request, command);
        });
    }

    /**
     * job-service가 알고 있는 사실만으로 거절한다. 공개·마감 공고, 이미 매칭이 진행된 공고, 결과를 아직 모르는 주문 생성,
     * 예치 확인·취소가 반영된 주문(DEPOSITED·REVIEW_REQUIRED)은 교체하지 않는다. READY·FAILED·CONFIRMING 여부는 job-service가 알 수
     * 없으므로 payment-service의 최종 판단에 맡긴다.
     */
    private void requireReplaceable(JobPost jobPost) {
        if (jobPost.getStatus() != JobStatus.PAYMENT_PENDING) {
            // 별도 재모집 정책 없이 공개 이후 지원·매칭이 진행된 조건을 바꾸지 않는다.
            throw new BusinessException(JobErrorCode.PAYMENT_CHANGE_NOT_ALLOWED,
                    "결제 대기 중인 공고만 결제 조건을 변경하거나 재결제할 수 있습니다.");
        }
        if (isOrderCreationInFlight(jobPost)) {
            throw new BusinessException(JobErrorCode.PAYMENT_CHANGE_IN_PROGRESS);
        }
        if (jobPost.getPaymentOrderId() == null) {
            throw new BusinessException(JobErrorCode.PAYMENT_CHANGE_NOT_ALLOWED, "연결된 결제 주문이 없는 공고입니다.");
        }
        if (jobPost.isFundingBlocked() || fundingRepository.findByOrderId(jobPost.getPaymentOrderId()).isPresent()) {
            throw new BusinessException(JobErrorCode.PAYMENT_CHANGE_NOT_ALLOWED,
                    "예치 확인 또는 결제 검토가 반영된 주문은 교체할 수 없습니다.");
        }
        if (hasRecruitmentActivity(jobPost)) {
            throw new BusinessException(JobErrorCode.PAYMENT_CHANGE_NOT_ALLOWED,
                    "지원 접수나 자리 예약이 있었던 공고의 결제 조건은 바꿀 수 없습니다.");
        }
    }

    // 같은 공고의 명령은 공고 행 잠금 아래에서 차례로 발급되므로 최신 명령이 미완료면 결과를 아직 모르는 주문 생성이 있다.
    private boolean isOrderCreationInFlight(JobPost jobPost) {
        return commandRepository.findFirstByJobPostIdOrderByIssueSequenceDesc(jobPost.getId())
                .filter(JobPaymentOrderCommand::isPending)
                .isPresent();
    }

    // 결제 대기 공고는 지원 승인·자리 예약을 받지 않으므로 정상 흐름에서는 없다. 방어적으로 확인해 진행된 매칭의 조건을 바꾸지 않는다.
    private boolean hasRecruitmentActivity(JobPost jobPost) {
        return admissionRepository.existsByJobPostId(jobPost.getId())
                || reservationRepository.existsByJobPostId(jobPost.getId());
    }

    private long calculateTotalDeposit(JobPaymentTerms terms) {
        long wagePerWorker = wageCalculator.calculateWagePerWorker(
                terms.startTime(),
                terms.endTime(),
                terms.endTimeNextDay(),
                terms.baseHourlyWage(),
                terms.extraWage()
        );
        return wageCalculator.calculateTotalExpectedWage(wagePerWorker, terms.recruitCount());
    }

    // 현재 시각에 따른 검증은 같은 키의 기존 요청 확인 뒤 새 요청에만 적용한다. 마감이 현재 이후이고 근무 시작 이전이어야 하므로
    // 지난 근무일도 여기서 거절된다.
    private void validateApplicationDeadline(JobPaymentTerms terms, LocalDateTime now) {
        if (!terms.applicationDeadline().isAfter(now)) {
            throw new BusinessException(JobErrorCode.INVALID_APPLICATION_DEADLINE, "지원 마감 시간은 현재 시간 이후여야 합니다.");
        }
        if (!terms.applicationDeadline().isBefore(terms.workDate().atTime(terms.startTime()))) {
            throw new BusinessException(JobErrorCode.INVALID_APPLICATION_DEADLINE, "지원 마감 시간은 근무 시작 시간 이전이어야 합니다.");
        }
    }

    // 결제용 버전은 결제 조건 스냅샷의 버전이다. 거절된 명령의 버전도 다시 쓰지 않도록 이 공고 명령의 최대값 다음을 쓴다.
    private long nextPaymentJobVersion(JobPost jobPost) {
        return commandRepository.findMaxJobVersionByJobPostId(jobPost.getId())
                .orElse(jobPost.getPaymentJobVersion()) + 1;
    }

    private JobPaymentChange issue(
            JobPost jobPost,
            String idempotencyKey,
            PaymentChangeType changeType,
            JobPaymentTerms terms,
            long amount,
            long paymentJobVersion,
            LocalDateTime now
    ) {
        JobPaymentOrderCommand command = commandIssuer.issue(jobPost, amount, paymentJobVersion, now);
        JobPaymentChangeRequest request = changeRequestRepository.save(JobPaymentChangeRequest.builder()
                .jobPostId(jobPost.getId())
                .ownerMemberId(jobPost.getOwnerId())
                .idempotencyKey(idempotencyKey)
                .changeType(changeType)
                .commandId(command.getId())
                .terms(terms)
                .build());
        return new JobPaymentChange(request, command);
    }
}
