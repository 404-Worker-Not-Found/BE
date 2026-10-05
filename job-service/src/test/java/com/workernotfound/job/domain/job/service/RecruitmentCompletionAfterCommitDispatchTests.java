package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobMatchingSeatReservation;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.RecruitmentCompletionCommand;
import com.workernotfound.job.domain.job.entity.enums.RecruitmentCompletionCommandStatus;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.domain.job.repository.RecruitmentCompletionCommandRepository;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import com.workernotfound.job.support.StubMatchingServer;
import com.workernotfound.job.support.StubMatchingServer.StubResponse;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 커밋 후 즉시 전송 경로. 자리 확정 요청 스레드는 matching-service 응답을 기다리지 않는다.
class RecruitmentCompletionAfterCommitDispatchTests extends IntegrationTestSupport {

    private static final StubMatchingServer SERVER = StubMatchingServer.start();

    @Autowired
    private MatchingSeatReservationCommandService seatService;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private RecruitmentCompletionCommandRepository commandRepository;

    @DynamicPropertySource
    static void enableAfterCommitDispatch(DynamicPropertyRegistry registry) {
        registry.add("job.matching-service.base-url", SERVER::baseUrl);
        registry.add("job.recruitment-completion.dispatch-after-commit", () -> "true");
    }

    @AfterAll
    void stopServer() {
        SERVER.close();
    }

    @Test
    void dispatchesAfterCommitWithoutBlockingConfirmation() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch received = new CountDownLatch(1);
        SERVER.onRequest(request -> {
            received.countDown();
            await(release);
        });
        JobPost jobPost = jobPostRepository.save(JobPostFixture.jobPost().recruitCount(1).build());
        JobMatchingSeatReservation reservation = seatService.reserve(
                jobPost.getId(), 800_001L, 800_002L, 100L, "seat-" + UUID.randomUUID());

        // 대역 서버가 응답을 붙잡고 있어도 확정 호출은 바로 끝나야 한다.
        seatService.confirm(jobPost.getId(), reservation.getId(), "confirm-" + UUID.randomUUID());
        RecruitmentCompletionCommand command = commandRepository.findByJobPostId(jobPost.getId()).get(0);
        assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
        release.countDown();

        assertThat(SERVER.requests()).singleElement().satisfies(request ->
                assertThat(request.header("Idempotency-Key")).isEqualTo(command.getCommandId()));
        awaitSucceeded(command);
    }

    @Test
    void sendsNothingWhenTheTransactionRollsBack() throws InterruptedException {
        SERVER.reset();
        SERVER.respondByDefault(StubResponse.success());
        JobPost jobPost = jobPostRepository.save(JobPostFixture.jobPost().recruitCount(1).build());
        JobMatchingSeatReservation reservation = seatService.reserve(
                jobPost.getId(), 800_003L, 800_004L, 100L, "seat-" + UUID.randomUUID());
        // 같은 완료 버전의 명령이 이미 있어 확정 트랜잭션이 롤백된다.
        commandRepository.save(RecruitmentCompletionCommand.builder()
                .commandId(UUID.randomUUID().toString())
                .jobPostId(jobPost.getId())
                .jobVersion(jobPost.getVersion() + 1)
                .nextAttemptAt(LocalDateTime.now().plusYears(1))
                .build());

        assertThatThrownBy(() -> seatService.confirm(
                jobPost.getId(), reservation.getId(), "confirm-" + UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 즉시 전송은 커밋 이후에만 예약되므로 잠시 기다려도 요청이 없어야 한다.
        Thread.sleep(300);
        assertThat(SERVER.requests()).isEmpty();
    }

    private void awaitSucceeded(RecruitmentCompletionCommand command) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (commandRepository.findById(command.getId()).orElseThrow().getStatus()
                    == RecruitmentCompletionCommandStatus.SUCCEEDED) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(commandRepository.findById(command.getId()).orElseThrow().getStatus())
                .isEqualTo(RecruitmentCompletionCommandStatus.SUCCEEDED);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
