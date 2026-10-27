package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.global.exception.BusinessException;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import com.workernotfound.job.support.MutableClock;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 지원 마감·근무 일시는 근무 일정 시간대(기본 Asia/Seoul)의 로컬 시각이다.
 * 실행 환경의 기본 시간대가 달라도 job-service의 현재 시각이 같은 시간대로 읽히는지 검증한다.
 */
class JobScheduleTimeZoneTests extends IntegrationTestSupport {

    private static final ZoneId SCHEDULE_ZONE = ZoneId.of("Asia/Seoul");
    private static final AtomicLong SEQUENCE = new AtomicLong(1_300_000);

    // 근무 시작 09:00, 지원 마감 07:00(한국 시간). 실제 현재 시각과 겹치지 않도록 며칠 뒤로 둔다.
    private final LocalDateTime workStart = LocalDate.now(SCHEDULE_ZONE).plusDays(5).atTime(9, 0);
    private final LocalDateTime deadline = workStart.minusHours(2);

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JobApplicationAdmissionCommandService admissionService;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private MutableClock clock;

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    @Test
    void productionClockUsesScheduleTimeZoneRegardlessOfJvmDefault() {
        assertThat(context.getBean("clock", Clock.class).getZone()).isEqualTo(SCHEDULE_ZONE);
        assertThat(clock.getZone()).isEqualTo(SCHEDULE_ZONE);
    }

    @Test
    void admissionDeadlineIsJudgedWithScheduleClock() {
        JobPost jobPost = saveOpenJob();

        clock.fixAt(deadline.minusNanos(1000).atZone(SCHEDULE_ZONE).toInstant());
        assertThat(admissionService.create(jobPost.getId(), SEQUENCE.incrementAndGet(), newKey()).getJobPostId())
                .isEqualTo(jobPost.getId());

        clock.fixAt(deadline.atZone(SCHEDULE_ZONE).toInstant());
        assertThatThrownBy(() -> admissionService.create(jobPost.getId(), SEQUENCE.incrementAndGet(), newKey()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(JobErrorCode.APPLICATION_DEADLINE_PASSED));
    }

    @Test
    void auditedTimestampsComeFromScheduleClock() {
        Instant fixed = LocalDateTime.of(2026, 3, 1, 9, 30).atZone(SCHEDULE_ZONE).toInstant();
        clock.fixAt(fixed);

        JobPost saved = saveOpenJob();

        assertThat(saved.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 3, 1, 9, 30));
        assertThat(saved.getUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 3, 1, 9, 30));
    }

    private JobPost saveOpenJob() {
        return jobPostRepository.saveAndFlush(JobPostFixture.withStatus(JobPostFixture.jobPost()
                .workDate(workStart.toLocalDate())
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .applicationDeadline(deadline)
                .build(), JobStatus.OPEN));
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }
}
