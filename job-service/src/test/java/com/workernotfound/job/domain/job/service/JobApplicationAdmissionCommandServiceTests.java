package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobApplicationAdmission;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.ApplicationAdmissionStatus;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.UrgencyLevel;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.domain.job.repository.JobApplicationAdmissionRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.global.exception.BusinessException;
import com.workernotfound.job.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobApplicationAdmissionCommandServiceTests extends IntegrationTestSupport {

    @Autowired
    private JobApplicationAdmissionCommandService jobApplicationAdmissionCommandService;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private JobApplicationAdmissionRepository jobApplicationAdmissionRepository;

    @Test
    void createsAdmissionForOpenJob() {
        JobPost jobPost = saveJobPost(JobStatus.OPEN, LocalDateTime.now().plusHours(1));

        JobApplicationAdmission admission =
                jobApplicationAdmissionCommandService.create(jobPost.getId(), 100L, "key-create");

        assertThat(admission.getId()).isNotNull();
        assertThat(admission.getJobPostId()).isEqualTo(jobPost.getId());
        assertThat(admission.getWorkerMemberId()).isEqualTo(100L);
        assertThat(admission.getStatus()).isEqualTo(ApplicationAdmissionStatus.RESERVED);
        assertThat(admission.getJobVersion()).isEqualTo(jobPost.getVersion());
        assertThat(admission.getExpiresAt()).isAfter(admission.getAdmittedAt());
    }

    @Test
    void throwsWhenJobNotFound() {
        assertThatThrownBy(() -> jobApplicationAdmissionCommandService.create(999_999L, 100L, "key-not-found"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(JobErrorCode.JOB_NOT_FOUND)
                );
    }

    @Test
    void throwsWhenJobNotOpen() {
        JobPost jobPost = saveJobPost(JobStatus.CLOSED, LocalDateTime.now().plusHours(1));

        assertThatThrownBy(() -> jobApplicationAdmissionCommandService.create(jobPost.getId(), 100L, "key-not-open"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(JobErrorCode.JOB_NOT_OPEN)
                );
    }

    @Test
    void throwsWhenApplicationDeadlinePassed() {
        JobPost jobPost = saveJobPost(JobStatus.OPEN, LocalDateTime.now().minusMinutes(1));

        assertThatThrownBy(() -> jobApplicationAdmissionCommandService.create(jobPost.getId(), 100L, "key-deadline"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(JobErrorCode.APPLICATION_DEADLINE_PASSED)
                );
    }

    @Test
    void reusesAdmissionForSameIdempotencyKey() {
        JobPost jobPost = saveJobPost(JobStatus.OPEN, LocalDateTime.now().plusHours(1));

        JobApplicationAdmission first =
                jobApplicationAdmissionCommandService.create(jobPost.getId(), 100L, "key-idempotent");
        JobApplicationAdmission second =
                jobApplicationAdmissionCommandService.create(jobPost.getId(), 100L, "key-idempotent");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(jobApplicationAdmissionRepository.findByIdempotencyKey("key-idempotent"))
                .isPresent()
                .get()
                .extracting(JobApplicationAdmission::getId)
                .isEqualTo(first.getId());
    }

    @Test
    void throwsAdmissionExpiredWhenExistingAdmissionExpired() {
        JobPost jobPost = saveJobPost(JobStatus.OPEN, LocalDateTime.now().plusHours(1));
        jobApplicationAdmissionRepository.save(
                JobApplicationAdmission.builder()
                        .jobPostId(jobPost.getId())
                        .workerMemberId(100L)
                        .idempotencyKey("key-expired")
                        .jobVersion(jobPost.getVersion())
                        .admittedAt(LocalDateTime.now().minusMinutes(10))
                        .expiresAt(LocalDateTime.now().minusMinutes(5))
                        .build()
        );

        assertThatThrownBy(() -> jobApplicationAdmissionCommandService.create(jobPost.getId(), 100L, "key-expired"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(JobErrorCode.ADMISSION_EXPIRED)
                );
    }

    private JobPost saveJobPost(JobStatus status, LocalDateTime applicationDeadline) {
        JobPost jobPost = JobPost.builder()
                .businessId(1L)
                .ownerId(1L)
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
                .latitude(BigDecimal.valueOf(37.5665))
                .longitude(BigDecimal.valueOf(126.9780))
                .urgencyLevel(UrgencyLevel.MEDIUM)
                .applicationDeadline(applicationDeadline)
                .build();
        if (status != JobStatus.OPEN) {
            ReflectionTestUtils.setField(jobPost, "status", status);
        }
        return jobPostRepository.save(jobPost);
    }
}
