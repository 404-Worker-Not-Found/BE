package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.dto.response.ApplicationAdmissionResponse;
import com.workernotfound.job.domain.job.entity.JobApplicationAdmission;
import com.workernotfound.job.domain.job.entity.JobPost;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

class JobApplicationAdmissionConstraintErrorTests extends IntegrationTestSupport {

    private static final Long MISSING_JOB_POST_ID = 999_999L;

    @Autowired
    private JobApplicationService jobApplicationService;

    @MockitoSpyBean
    private JobPostRepository jobPostRepository;

    @MockitoSpyBean
    private JobApplicationAdmissionRepository jobApplicationAdmissionRepository;

    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        executorService = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    @Test
    void mapsDuplicateIdempotencyKeyToConflictWhenConcurrentRequestsUseDifferentJobs() throws Exception {
        JobPost firstJobPost = saveJobPost();
        JobPost secondJobPost = saveJobPost();
        String idempotencyKey = newKey();
        // 공고가 서로 다르면 공고 행 잠금이 직렬화해 주지 않는다. 두 요청이 모두 멱등 키 조회를 지난 뒤에 저장하도록 맞춘다.
        CountDownLatch keyLookups = new CountDownLatch(2);
        doAnswer(invocation -> {
            keyLookups.countDown();
            assertThat(keyLookups.await(5, TimeUnit.SECONDS)).isTrue();
            return Optional.empty();
        }).when(jobApplicationAdmissionRepository).findByIdempotencyKey(idempotencyKey);

        List<Future<ApplicationAdmissionResponse>> requests = List.of(
                requestAdmission(firstJobPost.getId(), idempotencyKey),
                requestAdmission(secondJobPost.getId(), idempotencyKey)
        );

        List<Throwable> failures = new ArrayList<>();
        int successCount = 0;
        for (Future<ApplicationAdmissionResponse> request : requests) {
            try {
                request.get(30, TimeUnit.SECONDS);
                successCount++;
            } catch (ExecutionException exception) {
                failures.add(exception.getCause());
            }
        }

        assertThat(successCount).isOne();
        assertThat(failures).singleElement()
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.getErrorCode()).isEqualTo(JobErrorCode.IDEMPOTENCY_KEY_REUSED);
                    assertThat(failure.getCause()).isInstanceOf(DataIntegrityViolationException.class);
                });
        assertThat(admissionCount(idempotencyKey)).isOne();
    }

    @Test
    void keepsUnrelatedIntegrityViolationAsServerError() {
        doReturn(Optional.of(missingJobPost())).when(jobPostRepository).findByIdForUpdate(MISSING_JOB_POST_ID);

        assertThatThrownBy(() ->
                jobApplicationService.createApplicationAdmission(MISSING_JOB_POST_ID, 100L, newKey()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(BusinessException.class);
    }

    private Future<ApplicationAdmissionResponse> requestAdmission(Long jobPostId, String idempotencyKey) {
        return executorService.submit(() ->
                jobApplicationService.createApplicationAdmission(jobPostId, 100L, idempotencyKey));
    }

    private long admissionCount(String idempotencyKey) {
        return jobApplicationAdmissionRepository.findAll().stream()
                .map(JobApplicationAdmission::getIdempotencyKey)
                .filter(idempotencyKey::equals)
                .count();
    }

    private String newKey() {
        return "admission-constraint-" + UUID.randomUUID();
    }

    private JobPost saveJobPost() {
        return jobPostRepository.save(newJobPost());
    }

    // 존재하지 않는 공고를 조회 결과로 돌려주면 승인 저장이 외래 키 제약을 위반한다.
    private JobPost missingJobPost() {
        JobPost jobPost = newJobPost();
        ReflectionTestUtils.setField(jobPost, "id", MISSING_JOB_POST_ID);
        return jobPost;
    }

    private JobPost newJobPost() {
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
                .applicationDeadline(LocalDateTime.now().plusHours(1))
                .build();
    }
}
