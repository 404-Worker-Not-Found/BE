package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobMatchingSeatReservation;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.MatchingSeatReservationStatus;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.domain.job.repository.JobMatchingSeatReservationRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.global.exception.BusinessException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매칭 확정 Saga의 모집 자리 예약 명령을 처리한다.
 *
 * <p>모든 변경은 공고 행 → 예약 행 순서로 잠근다. 같은 공고의 예약·확정·반환·만료 회수가 공고 행 잠금으로
 * 직렬화되므로 {@code CONSUMED 수 + 유효한 RESERVED 수 <= recruitCount}가 유지된다.
 * 마지막 자리 확정({@code CONSUMED 수 == recruitCount})은 같은 잠금과 트랜잭션에서 공고를 모집 완료로 마감한다.
 */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(MatchingSeatReservationProperties.class)
public class MatchingSeatReservationCommandService {

    private static final String CURRENCY_KRW = "KRW";
    private static final Set<JobStatus> MATCHABLE_STATUSES = EnumSet.of(JobStatus.OPEN, JobStatus.MATCHING);
    private static final Set<MatchingSeatReservationStatus> OCCUPYING_STATUSES =
            EnumSet.of(MatchingSeatReservationStatus.RESERVED, MatchingSeatReservationStatus.CONSUMED);

      private final com.workernotfound.job.global.account.AccountGateService accountGates;
  private final JobPostRepository jobPostRepository;
    private final JobMatchingSeatReservationRepository reservationRepository;
    private final JobWageCalculator jobWageCalculator;
    private final JobRecruitmentCompletionService recruitmentCompletionService;
    private final MatchingSeatReservationProperties properties;
    private final Clock clock;

    @Transactional
    public JobMatchingSeatReservation reserve(
            Long jobPostId,
            Long matchingId,
            Long applicationId,
            Long workerMemberId,
            String idempotencyKey
    ) {
        // 공고 행 잠금을 먼저 잡아야 같은 키의 동시 최초 요청이 앞선 커밋 결과를 조회할 수 있다.
        JobPost jobPost = lockJobPost(jobPostId);
        accountGates.requireActive(jobPost.getOwnerId(), workerMemberId);
        Optional<JobMatchingSeatReservation> existing = reservationRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replayReservation(existing.get(), jobPostId, matchingId, applicationId, workerMemberId);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        validateMatchable(jobPost, now);
        // 스케줄러가 늦어도 만료된 예약이 새 예약을 막지 않도록 같은 잠금 안에서 먼저 회수한다.
        expireOverdueReservations(jobPostId, now);
        validateNotHeld(jobPostId, matchingId, applicationId);
        validateSeatAvailable(jobPost);

        // 발급 시각을 저장 정밀도로 절삭한 뒤 TTL을 더하고 다시 절삭한다. 재요청은 이 값을 그대로 돌려준다.
        LocalDateTime reservedAt = JobMatchingSeatReservation.toStoredTime(now);
        LocalDateTime expiresAt = JobMatchingSeatReservation.toStoredTime(reservedAt.plus(properties.ttl()));
        return reservationRepository.save(JobMatchingSeatReservation.builder()
                .jobPostId(jobPostId)
                .matchingId(matchingId)
                .applicationId(applicationId)
                .workerMemberId(workerMemberId)
                .idempotencyKey(idempotencyKey)
                .jobVersion(jobPost.getVersion())
                .ownerMemberId(jobPost.getOwnerId())
                .workDate(jobPost.getWorkDate())
                .startTime(jobPost.getStartTime())
                .endTime(jobPost.getEndTime())
                .endTimeNextDay(jobPost.isEndTimeNextDay())
                .lockedAmount(jobWageCalculator.calculateWagePerWorker(jobPost))
                .currency(CURRENCY_KRW)
                .reservedAt(reservedAt)
                .expiresAt(expiresAt)
                .build());
    }

    @Transactional
    public JobMatchingSeatReservation confirm(Long jobPostId, Long reservationId, String idempotencyKey) {
        JobPost jobPost = lockJobPost(jobPostId);
        validateCommandKeyOwner(reservationRepository.findByConfirmIdempotencyKey(idempotencyKey), reservationId);
        JobMatchingSeatReservation reservation = lockReservation(jobPostId, reservationId);
        LocalDateTime now = LocalDateTime.now(clock);

        switch (reservation.getStatus()) {
            // 확정 응답이 유실된 재요청이다. 원래 만료 시각이 지났거나 공고가 이미 모집 완료로 마감됐어도 거절하지 않는다.
            case CONSUMED -> requireRecordedKey(reservation.getConfirmIdempotencyKey(), idempotencyKey);
            case RESERVED -> consumeAndCompleteIfFilled(jobPost, reservation, idempotencyKey, now);
            case EXPIRED -> throw new BusinessException(JobErrorCode.SEAT_RESERVATION_EXPIRED);
            case RELEASED -> throw new BusinessException(
                    JobErrorCode.SEAT_RESERVATION_STATE_CONFLICT, "반환된 모집 자리 예약은 확정할 수 없습니다.");
        }
        return reservation;
    }

    @Transactional
    public JobMatchingSeatReservation release(Long jobPostId, Long reservationId, String idempotencyKey) {
        lockJobPost(jobPostId);
        validateCommandKeyOwner(reservationRepository.findByReleaseIdempotencyKey(idempotencyKey), reservationId);
        JobMatchingSeatReservation reservation = lockReservation(jobPostId, reservationId);
        LocalDateTime now = LocalDateTime.now(clock);

        switch (reservation.getStatus()) {
            case RESERVED -> releaseReserved(reservation, idempotencyKey, now);
            case RELEASED -> requireRecordedKey(reservation.getReleaseIdempotencyKey(), idempotencyKey);
            // 만료로 이미 자리가 회수되었으므로 반환 목적은 달성됐다. 처음 들어온 반환 키만 기록한다.
            case EXPIRED -> recordReleaseOfExpired(reservation, idempotencyKey);
            case CONSUMED -> throw new BusinessException(
                    JobErrorCode.SEAT_RESERVATION_STATE_CONFLICT, "확정된 모집 자리 예약은 반환할 수 없습니다.");
        }
        return reservation;
    }

    @Transactional
    public int expireOverdue(Long jobPostId) {
        lockJobPost(jobPostId);
        return expireOverdueReservations(jobPostId, LocalDateTime.now(clock));
    }

    // 범위 조건 UPDATE는 보조 인덱스 gap 잠금을 잡아 다른 공고의 예약 INSERT와 교착될 수 있다.
    // 공고 행 잠금 아래에서 대상 ID를 비잠금 조회한 뒤 기본 키로만 갱신한다.
    // 저장된 expires_at은 항상 마이크로초 단위이므로 expiresAt <= now와 expiresAt <= 절삭한 now는 같은 판정이다.
    // 쿼리 인자를 절삭해 두어야 MySQL이 나노초 인자를 반올림해 경계가 앞당겨지지 않는다. 회수 시각도 같은 값으로 저장한다.
    private int expireOverdueReservations(Long jobPostId, LocalDateTime now) {
        LocalDateTime storedNow = JobMatchingSeatReservation.toStoredTime(now);
        List<Long> overdueIds = reservationRepository.findOverdueIdsByJobPostId(jobPostId, storedNow);
        if (overdueIds.isEmpty()) {
            return 0;
        }
        return reservationRepository.expireByIdIn(overdueIds, storedNow);
    }

    // 같은 키의 동일 요청은 상태와 관계없이 저장된 스냅샷을 그대로 돌려준다. 종료된 예약을 되살리거나 새로 발급하지 않는다.
    private JobMatchingSeatReservation replayReservation(
            JobMatchingSeatReservation reservation,
            Long jobPostId,
            Long matchingId,
            Long applicationId,
            Long workerMemberId
    ) {
        if (!reservation.isSameReservationRequest(jobPostId, matchingId, applicationId, workerMemberId)) {
            throw new BusinessException(JobErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return reservation;
    }

    // 마지막 자리 확정이면 같은 트랜잭션에서 공고 마감·상태 이력·모집 완료 알림 명령까지 저장한다.
    private void consumeAndCompleteIfFilled(
            JobPost jobPost,
            JobMatchingSeatReservation reservation,
            String idempotencyKey,
            LocalDateTime now
    ) {
        if (reservation.isExpiredAt(now)) {
            throw new BusinessException(JobErrorCode.SEAT_RESERVATION_EXPIRED);
        }
        // 공고 행 잠금 아래에서 예치 차단을 확인한다. 차단 뒤의 최초 확정은 거절하고, Saga가 4xx 거절로 보고 자리를 반환·보상한다.
        // 이미 CONSUMED인 예약의 같은 키 재요청은 이 검사 전에 성공하므로 차단이 확정 결과를 되돌리지 않는다.
        validateFundingNotBlocked(jobPost);
        reservation.consume(idempotencyKey, now);
        recruitmentCompletionService.completeIfFilled(jobPost, now);
    }

    private void releaseReserved(JobMatchingSeatReservation reservation, String idempotencyKey, LocalDateTime now) {
        if (reservation.isExpiredAt(now)) {
            reservation.expire(now);
            reservation.recordReleaseOfExpired(idempotencyKey);
            return;
        }
        reservation.release(idempotencyKey, now);
    }

    private void recordReleaseOfExpired(JobMatchingSeatReservation reservation, String idempotencyKey) {
        if (reservation.getReleaseIdempotencyKey() == null) {
            reservation.recordReleaseOfExpired(idempotencyKey);
            return;
        }
        requireRecordedKey(reservation.getReleaseIdempotencyKey(), idempotencyKey);
    }

    // 같은 키가 다른 예약의 확정·반환에 이미 쓰였으면 멱등 키 재사용이다.
    private void validateCommandKeyOwner(Optional<JobMatchingSeatReservation> keyOwner, Long reservationId) {
        if (keyOwner.isPresent() && !keyOwner.get().getId().equals(reservationId)) {
            throw new BusinessException(JobErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
    }

    // 이미 처리된 예약에 다른 키가 오면 저장된 키를 덮어쓰지 않고 거절한다. 원래 명령의 재시도만 성공한다.
    private void requireRecordedKey(String recordedKey, String idempotencyKey) {
        if (!idempotencyKey.equals(recordedKey)) {
            throw new BusinessException(
                    JobErrorCode.SEAT_RESERVATION_STATE_CONFLICT, "이미 다른 명령으로 처리된 모집 자리 예약입니다.");
        }
    }

    private JobMatchingSeatReservation lockReservation(Long jobPostId, Long reservationId) {
        return reservationRepository.findByIdForUpdate(reservationId)
                .filter(reservation -> reservation.getJobPostId().equals(jobPostId))
                .orElseThrow(() -> new BusinessException(JobErrorCode.SEAT_RESERVATION_NOT_FOUND));
    }

    private void validateMatchable(JobPost jobPost, LocalDateTime now) {
        if (!MATCHABLE_STATUSES.contains(jobPost.getStatus())) {
            throw new BusinessException(JobErrorCode.JOB_NOT_MATCHABLE);
        }
        validateFundingNotBlocked(jobPost);
        // 지원 마감이 아니라 근무 시작 시각을 자리 예약 기한으로 사용한다.
        if (!jobPost.getWorkDate().atTime(jobPost.getStartTime()).isAfter(now)) {
            throw new BusinessException(JobErrorCode.WORK_ALREADY_STARTED);
        }
    }

    // 예치 취소·검토 필요가 확인된 공고는 OPEN·MATCHING이어도 새 자리 예약과 최초 확정을 받지 않는다.
    private void validateFundingNotBlocked(JobPost jobPost) {
        if (jobPost.isFundingBlocked()) {
            throw new BusinessException(JobErrorCode.JOB_NOT_MATCHABLE, "예치 확인이 필요해 매칭을 진행할 수 없는 공고입니다.");
        }
    }

    private void validateNotHeld(Long jobPostId, Long matchingId, Long applicationId) {
        boolean isHeld = reservationRepository.existsByJobPostIdAndStatusInAndMatchingOrApplication(
                jobPostId, OCCUPYING_STATUSES, matchingId, applicationId);
        if (isHeld) {
            throw new BusinessException(JobErrorCode.SEAT_ALREADY_HELD);
        }
    }

    private void validateSeatAvailable(JobPost jobPost) {
        long occupied = reservationRepository.countByJobPostIdAndStatusIn(jobPost.getId(), OCCUPYING_STATUSES);
        if (occupied >= jobPost.getRecruitCount()) {
            throw new BusinessException(JobErrorCode.RECRUITMENT_SEAT_UNAVAILABLE);
        }
    }

    private JobPost lockJobPost(Long jobPostId) {
        return jobPostRepository.findByIdForUpdate(jobPostId)
                .orElseThrow(() -> new BusinessException(JobErrorCode.JOB_NOT_FOUND));
    }
}
