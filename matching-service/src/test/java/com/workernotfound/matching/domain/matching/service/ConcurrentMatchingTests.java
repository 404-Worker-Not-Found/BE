package com.workernotfound.matching.domain.matching.service;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.application.repository.ApplicationStatusHistoryRepository;
import com.workernotfound.matching.domain.application.service.ApplicationCommandService;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingStatus;
import com.workernotfound.matching.domain.matching.repository.MatchingRepository;
import com.workernotfound.matching.domain.matching.repository.MatchingStatusHistoryRepository;
import com.workernotfound.matching.domain.outbox.repository.OutboxEventRepository;
import com.workernotfound.matching.domain.score.repository.MatchingScoreBatchRepository;
import com.workernotfound.matching.domain.score.repository.MatchingScoreSnapshotRepository;
import com.workernotfound.matching.global.exception.BusinessException;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentMatchingTests extends IntegrationTestSupport {

	@Autowired
	private MatchingCommandService matchingCommandService;

	@Autowired
	private MatchingDeclineService matchingDeclineService;

	@Autowired
	private ApplicationCommandService applicationCommandService;

	@Autowired
	private MatchingRepository matchingRepository;

	@Autowired
	private MatchingStatusHistoryRepository matchingHistoryRepository;

	@Autowired
	private MatchingScoreSnapshotRepository scoreSnapshotRepository;

	@Autowired
	private MatchingScoreBatchRepository scoreBatchRepository;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Autowired
	private ApplicationStatusHistoryRepository applicationHistoryRepository;

	@Autowired
	private ApplicationRepository applicationRepository;

	private ExecutorService executorService;

	@BeforeEach
	void setUp() {
		deletePersistence();
		executorService = Executors.newFixedThreadPool(2);
	}

	@AfterEach
	void tearDown() {
		executorService.shutdownNow();
		deletePersistence();
	}

	@Test
	void selectionAndCancellationCannotLeavePendingMatchingForCanceledApplication() throws Exception {
		Application application = applicationRepository.saveAndFlush(Application.builder()
			.jobPostId(10L)
			.workerMemberId(20L)
			.ownerMemberId(100L)
			.jobApplicationAdmissionId(30L)
			.appliedAt(LocalDateTime.now())
			.build());
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		Future<Boolean> selection = executorService.submit(() -> runTogether(ready, start, () ->
			matchingCommandService.createManual(10L, application.getId(), 100L, null)));
		Future<Boolean> cancellation = executorService.submit(() -> runTogether(ready, start, () ->
			applicationCommandService.cancel(application.getId(), 20L, "cancel-command")));
		assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
		start.countDown();

		selection.get(10, TimeUnit.SECONDS);
		assertThat(cancellation.get(10, TimeUnit.SECONDS)).isTrue();
		Application latest = applicationRepository.findById(application.getId()).orElseThrow();
		assertThat(latest.getStatus()).isEqualTo(ApplicationStatus.CANCELED);
		matchingRepository.findByApplicationId(application.getId()).ifPresent(matching ->
			assertThat(matching.getStatus()).isEqualTo(MatchingStatus.CANCELED));
	}

	@Test
	void declineAndCancellationCannotLeavePendingMatchingForCanceledApplication() throws Exception {
		Application application = applicationRepository.saveAndFlush(Application.builder()
			.jobPostId(10L)
			.workerMemberId(20L)
			.ownerMemberId(100L)
			.jobApplicationAdmissionId(30L)
			.appliedAt(LocalDateTime.now())
			.build());
		Long matchingId = matchingCommandService.createManual(10L, application.getId(), 100L, null).getId();
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		Future<Boolean> decline = executorService.submit(() -> runTogether(ready, start, () ->
			matchingDeclineService.decline(matchingId, 20L)));
		Future<Boolean> cancellation = executorService.submit(() -> runTogether(ready, start, () ->
			applicationCommandService.cancel(application.getId(), 20L, "cancel-command")));
		assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
		start.countDown();

		decline.get(10, TimeUnit.SECONDS);
		assertThat(cancellation.get(10, TimeUnit.SECONDS)).isTrue();
		assertThat(applicationRepository.findById(application.getId()).orElseThrow().getStatus())
			.isEqualTo(ApplicationStatus.CANCELED);
		assertThat(matchingRepository.findById(matchingId).orElseThrow().getStatus())
			.isIn(MatchingStatus.DECLINED, MatchingStatus.CANCELED);
	}

	private boolean runTogether(CountDownLatch ready, CountDownLatch start, Runnable command) {
		ready.countDown();
		try {
			if (!start.await(5, TimeUnit.SECONDS)) {
				return false;
			}
			command.run();
			return true;
		} catch (BusinessException exception) {
			return false;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private void deletePersistence() {
		matchingHistoryRepository.deleteAll();
		matchingRepository.deleteAll();
		scoreSnapshotRepository.deleteAll();
		scoreBatchRepository.deleteAll();
		outboxEventRepository.deleteAll();
		applicationHistoryRepository.deleteAll();
		applicationRepository.deleteAll();
	}
}
