package com.workernotfound.job.support;

import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.UrgencyLevel;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.springframework.test.util.ReflectionTestUtils;

public final class JobPostFixture {

    private JobPostFixture() {
    }

    // 내일 09:00~18:00, 시급 10,000원(1인 90,000원) 공고를 기본값으로 한다.
    public static JobPost.JobPostBuilder jobPost() {
        return JobPost.builder()
                .businessId(1L)
                .ownerId(7L)
                .categoryId(1L)
                .storeName("테스트 상점")
                .address("서울시 마포구")
                .title("테스트 공고")
                .description("테스트 설명")
                .workDate(LocalDate.now().plusDays(1))
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .endTimeNextDay(false)
                .baseHourlyWage(10_000)
                .recruitCount(1)
                .latitude(new BigDecimal("37.5665000"))
                .longitude(new BigDecimal("126.9780000"))
                .urgencyLevel(UrgencyLevel.MEDIUM)
                .applicationDeadline(LocalDateTime.now().plusHours(1));
    }

    // 신규 공고는 결제 대기(PAYMENT_PENDING)로 생성된다. 공개 이후의 동작을 검증하는 테스트는 공개 상태를 명시한다.
    public static JobPost open(JobPost jobPost) {
        return withStatus(jobPost, JobStatus.OPEN);
    }

    public static JobPost withStatus(JobPost jobPost, JobStatus status) {
        ReflectionTestUtils.setField(jobPost, "status", status);
        return jobPost;
    }
}
