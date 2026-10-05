package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.repository.JobFundingStatusReceiptRepository;
import com.workernotfound.job.global.exception.GlobalExceptionHandler;
import com.workernotfound.job.support.FundingStatusApiTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 서로 다른 공고에 같은 멱등 키로 동시에 들어온 최초 예치 상태 알림. 공고 행이 달라 잠금이 직렬화하지 않으므로 유일 제약이 마지막으로
 * 막고, 롤백 뒤 전역 예외 처리 경계에서 JOB-409-004로 변환되는지 확인한다.
 */
class FundingStatusConstraintErrorTests extends FundingStatusApiTestSupport {

    @MockitoSpyBean
    private GlobalExceptionHandler globalExceptionHandler;

    @MockitoSpyBean
    private JobFundingStatusReceiptRepository spiedReceiptRepository;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(globalExceptionHandler).handleException(any(), any());
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void mapsConcurrentKeyReuseAcrossJobsToConflictAfterRollback() throws Exception {
        LinkedJob first = createLinkedJob(1);
        LinkedJob second = createLinkedJob(1);
        String key = newKey("shared-funding-key");
        // 두 요청이 모두 키 조회를 지난 뒤에 저장하도록 맞춘다.
        CountDownLatch keyLookups = new CountDownLatch(2);
        doAnswer(invocation -> {
            keyLookups.countDown();
            assertThat(keyLookups.await(5, TimeUnit.SECONDS)).isTrue();
            return Optional.empty();
        }).when(spiedReceiptRepository).findByIdempotencyKey(key);

        List<Future<MvcResult>> requests = List.of(
                executor.submit(() -> sendFunding(first.jobPostId(), key, notice(first, 1, true)).andReturn()),
                executor.submit(() -> sendFunding(second.jobPostId(), key, notice(second, 1, true)).andReturn()));
        List<MvcResult> results = new ArrayList<>();
        for (Future<MvcResult> request : requests) {
            results.add(request.get(30, TimeUnit.SECONDS));
        }

        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(200, 409);
        MvcResult conflict = results.stream()
                .filter(result -> result.getResponse().getStatus() == 409).findFirst().orElseThrow();
        jsonPath("$.code").value("JOB-409-004").match(conflict);
        assertThat(conflict.getResolvedException()).isInstanceOf(DataIntegrityViolationException.class);
        verify(globalExceptionHandler).handleException(any(), any());
        // 거절된 쪽 공고의 공개·이력·revision도 함께 롤백된다.
        List<JobStatus> statuses = List.of(
                jobPost(first.jobPostId()).getStatus(), jobPost(second.jobPostId()).getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(JobStatus.OPEN, JobStatus.PAYMENT_PENDING);
        long histories = historyRepository.findByJobPostIdOrderByIdAsc(first.jobPostId()).size()
                + historyRepository.findByJobPostIdOrderByIdAsc(second.jobPostId()).size();
        assertThat(histories).isOne();
        long fundings = fundingRepository.findByOrderId(first.orderId()).stream().count()
                + fundingRepository.findByOrderId(second.orderId()).stream().count();
        assertThat(fundings).isOne();
    }
}
