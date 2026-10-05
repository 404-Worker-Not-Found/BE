package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.dto.request.CreateJobRequest;
import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;
import com.workernotfound.job.domain.job.entity.enums.PaymentOrderCommandStatus;
import com.workernotfound.job.domain.job.repository.JobPaymentOrderCommandRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.StubPaymentServer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

// 공고 생성 커밋 직후 별도 스레드에서 주문 생성을 한 번 시도한다. 공고 등록은 payment-service 응답을 기다리지 않는다.
class PaymentOrderAfterCommitDispatchTests extends IntegrationTestSupport {

    private static final StubPaymentServer SERVER = StubPaymentServer.start();

    @Autowired
    private JobCommandService jobCommandService;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private JobPaymentOrderCommandRepository commandRepository;

    @DynamicPropertySource
    static void enableAfterCommitDispatch(DynamicPropertyRegistry registry) {
        registry.add("job.payment-service.base-url", SERVER::baseUrl);
        registry.add("job.payment-order.dispatch-after-commit", () -> "true");
    }

    @AfterAll
    void stopServer() {
        SERVER.close();
    }

    @Test
    void dispatchesAfterCommitWithoutBlockingJobCreation() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch received = new CountDownLatch(1);
        SERVER.onRequest(request -> {
            received.countDown();
            await(release);
        });

        // 대역 서버가 응답을 붙잡고 있어도 공고 등록은 바로 끝나야 한다.
        Long jobPostId = jobCommandService.create(940_001L, request());
        JobPaymentOrderCommand command = commandRepository.findByJobPostIdOrderByIssueSequenceAsc(jobPostId).get(0);
        assertThat(jobPostRepository.findById(jobPostId).orElseThrow().getPaymentOrderId()).isNull();
        assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
        release.countDown();

        awaitLinked(command);
        assertThat(jobPostRepository.findById(jobPostId).orElseThrow().getPaymentOrderId())
                .isEqualTo(SERVER.issuedOrderId(command.getIdempotencyKey()));
    }

    private void awaitLinked(JobPaymentOrderCommand command) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (commandRepository.findById(command.getId()).orElseThrow().getStatus()
                    == PaymentOrderCommandStatus.SUCCEEDED) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(commandRepository.findById(command.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderCommandStatus.SUCCEEDED);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private CreateJobRequest request() {
        LocalDate workDate = LocalDate.now().plusDays(2);
        return new CreateJobRequest(
                1L, 1L, "테스트 상점", "서울시 마포구", "커밋 후 전송 공고", "설명",
                workDate, LocalTime.of(9, 0), LocalTime.of(18, 0), false, 10_320, null, 1,
                new BigDecimal("37.5665000"), new BigDecimal("126.9780000"), "MEDIUM",
                LocalDateTime.of(workDate.minusDays(1), LocalTime.NOON));
    }
}
