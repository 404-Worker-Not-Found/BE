package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.dto.request.CreateJobRequest;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.UrgencyLevel;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.domain.job.port.BusinessValidator;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class JobCommandService {

    private final JobPostRepository jobPostRepository;
    private final BusinessValidator businessValidator;
    private final CategoryFindService categoryFindService;
    private final JobWageCalculator jobWageCalculator;
    private final JobPaymentOrderCommandIssuer paymentOrderCommandIssuer;
    private final Clock clock;

    /**
     * 공고를 결제 대기(비공개)로 저장하고 전체 예치 예정액의 결제 주문 생성 명령을 같은 트랜잭션에 저장한다.
     * payment-service 호출은 커밋 이후 트랜잭션 밖에서 실행되므로 이 메서드는 주문 생성 결과를 기다리지 않는다.
     */
    @Transactional
    public Long create(Long ownerId, CreateJobRequest request) {
        businessValidator.validateOwnership(request.businessId(), ownerId);
        categoryFindService.findCategory(request.categoryId());
        long totalDeposit = calculateTotalDeposit(request);
        validateApplicationDeadline(request);

        JobPost jobPost = JobPost.builder()
                .businessId(request.businessId())
                .ownerId(ownerId)
                .categoryId(request.categoryId())
                .storeName(request.storeName())
                .address(request.address())
                .title(request.title())
                .description(request.description())
                .workDate(request.workDate())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .endTimeNextDay(request.isEndTimeNextDay())
                .baseHourlyWage(request.baseHourlyWage())
                .extraWage(request.extraWage())
                .recruitCount(request.recruitCount())
                .latitude(request.latitude())
                .longitude(request.longitude())
                .urgencyLevel(UrgencyLevel.valueOf(request.urgencyLevel()))
                .applicationDeadline(request.applicationDeadline())
                .build();
        // 저장된 버전을 결제용 버전으로 고정하기 위해 INSERT를 먼저 반영한다.
        JobPost saved = jobPostRepository.saveAndFlush(jobPost);
        paymentOrderCommandIssuer.issue(saved, totalDeposit, LocalDateTime.now(clock));
        return saved.getId();
    }

    // 근무 구간과 급여 금액을 계산할 수 없거나 전체 예치 예정액이 허용 범위를 벗어난 공고는 등록 시 거절한다.
    private long calculateTotalDeposit(CreateJobRequest request) {
        long wagePerWorker = jobWageCalculator.calculateWagePerWorker(
                request.startTime(),
                request.endTime(),
                request.isEndTimeNextDay(),
                request.baseHourlyWage(),
                request.extraWage()
        );
        return jobWageCalculator.calculateTotalExpectedWage(wagePerWorker, request.recruitCount());
    }

    private void validateApplicationDeadline(CreateJobRequest request) {
        LocalDateTime now = LocalDateTime.now();
        if (!request.applicationDeadline().isAfter(now)) {
            throw new BusinessException(JobErrorCode.INVALID_APPLICATION_DEADLINE, "지원 마감 시간은 현재 시간 이후여야 합니다.");
        }
        if (!request.applicationDeadline().isBefore(request.workDate().atTime(request.startTime()))) {
            throw new BusinessException(JobErrorCode.INVALID_APPLICATION_DEADLINE, "지원 마감 시간은 근무 시작 시간 이전이어야 합니다.");
        }
    }
}
