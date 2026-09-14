package com.workernotfound.matching.domain.application.service;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.domain.application.exception.ApplicationErrorCode;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.application.repository.ApplicationStatusHistoryRepository;
import com.workernotfound.matching.domain.matching.entity.Matching;
import com.workernotfound.matching.domain.matching.entity.MatchingConfirmationSaga;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingConfirmationRecoveryAction;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingSelectionType;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingStatus;
import com.workernotfound.matching.domain.matching.repository.MatchingConfirmationSagaRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecruitmentCompletionServiceTests extends IntegrationTestSupport {

	@Autowired
	private RecruitmentCompletionService recruitmentCompletionService;

	@Autowired
	private ApplicationRepository applicationRepository;

	@Autowired
	private ApplicationStatusHistoryRepository applicationHistoryRepository;

	@Autowired
	private MatchingRepository matchingRepository;

	@Autowired
	private MatchingStatusHistoryRepository matchingHistoryRepository;

	@Autowired
	private MatchingConfirmationSagaRepository sagaRepository;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Autowired
	private MatchingScoreSnapshotRepository scoreSnapshotRepository;

	@Autowired
	private MatchingScoreBatchRepository scoreBatchRepository;

	@BeforeEach
	void setUp() {
		deletePersistence();
	}

	@AfterEach
	void tearDown() {
		deletePersistence();
	}

	@Test
	void rejectsAppliedCandidatesAndCancelsPendingProposalsWhenRecruitmentCompletes() {
		Application selected = saveApplication(10L, 20L, 1L);
		selected.select();
		applicationRepository.saveAndFlush(selected);
		Application withoutMatching = saveApplication(10L, 21L, 2L);
		Application withPendingMatching = saveApplication(10L, 22L, 3L);
		Matching pending = saveMatching(withPendingMatching);
		Application withDeclinedMatching = saveApplication(10L, 23L, 4L);
		Matching declined = saveMatching(withDeclinedMatching);
		declined.decline();
		matchingRepository.saveAndFlush(declined);

		recruitmentCompletionService.complete(10L, "completion-command");

		assertThat(statusOf(selected)).isEqualTo(ApplicationStatus.SELECTED);
		assertThat(statusOf(withoutMatching)).isEqualTo(ApplicationStatus.REJECTED);
		assertThat(statusOf(withPendingMatching)).isEqualTo(ApplicationStatus.REJECTED);
		assertThat(statusOf(withDeclinedMatching)).isEqualTo(ApplicationStatus.REJECTED);
		assertThat(matchingStatusOf(pending)).isEqualTo(MatchingStatus.CANCELED);
		assertThat(matchingStatusOf(declined)).isEqualTo(MatchingStatus.DECLINED);
		assertThat(applicationHistoryRepository.findAll())
			.extracting(history -> history.getToStatus())
			.containsExactlyInAnyOrder(
				ApplicationStatus.REJECTED,
				ApplicationStatus.REJECTED,
				ApplicationStatus.REJECTED
			);
		assertThat(matchingHistoryRepository.findAll())
			.extracting(history -> history.getToStatus())
			.containsExactly(MatchingStatus.CANCELED);
		assertThat(outboxEventRepository.findAll())
			.extracting(event -> event.getEventType())
			.containsOnly("ApplicationRejected");
	}

	@Test
	void repeatedRecruitmentCompletionDoesNotCreateDuplicateHistoryOrEvents() {
		Application application = saveApplication(11L, 20L, 5L);
		recruitmentCompletionService.complete(11L, "completion-command");

		recruitmentCompletionService.complete(11L, "completion-command");

		assertThat(statusOf(application)).isEqualTo(ApplicationStatus.REJECTED);
		assertThat(applicationHistoryRepository.count()).isOne();
		assertThat(outboxEventRepository.count()).isOne();
	}

	@Test
	void doesNotRejectAnyCandidateWhileAConfirmationOutcomeMustBeRecovered() {
		Application first = saveApplication(12L, 20L, 6L);
		Application second = saveApplication(12L, 21L, 7L);
		Matching matching = saveMatching(second);
		MatchingConfirmationSaga saga = sagaRepository.saveAndFlush(saga(matching));
		saga.fail(
			"SEAT_RESERVATION",
			"response lost",
			MatchingConfirmationRecoveryAction.RESUME_PROCESSING
		);
		sagaRepository.flush();

		assertThatThrownBy(() -> recruitmentCompletionService.complete(12L, "completion-command"))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.getErrorCode())
					.isEqualTo(ApplicationErrorCode.RECRUITMENT_COMPLETION_IN_PROGRESS));

		assertThat(statusOf(first)).isEqualTo(ApplicationStatus.APPLIED);
		assertThat(statusOf(second)).isEqualTo(ApplicationStatus.APPLIED);
		assertThat(matchingStatusOf(matching)).isEqualTo(MatchingStatus.PENDING);
		assertThat(applicationHistoryRepository.count()).isZero();
		assertThat(outboxEventRepository.count()).isZero();
	}

	@Test
	void concurrentCompletionCommandsCreateOneHistoryAndEvent() throws Exception {
		Application application = saveApplication(13L, 20L, 8L);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Future<?> first = executor.submit(() -> completeTogether(13L, ready, start));
			Future<?> second = executor.submit(() -> completeTogether(13L, ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			first.get(10, TimeUnit.SECONDS);
			second.get(10, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}

		assertThat(statusOf(application)).isEqualTo(ApplicationStatus.REJECTED);
		assertThat(applicationHistoryRepository.count()).isOne();
		assertThat(outboxEventRepository.count()).isOne();
	}

	private Application saveApplication(Long jobPostId, Long workerMemberId, Long admissionId) {
		return applicationRepository.saveAndFlush(Application.builder()
			.jobPostId(jobPostId)
			.workerMemberId(workerMemberId)
			.ownerMemberId(100L)
			.jobApplicationAdmissionId(admissionId)
			.appliedAt(LocalDateTime.now().minusMinutes(10))
			.build());
	}

	private Matching saveMatching(Application application) {
		return matchingRepository.saveAndFlush(Matching.builder()
			.application(application)
			.selectionType(MatchingSelectionType.MANUAL)
			.selectedAt(LocalDateTime.now().minusMinutes(1))
			.build());
	}

	private MatchingConfirmationSaga saga(Matching matching) {
		return MatchingConfirmationSaga.builder()
			.matching(matching)
			.seatReservationCommandId("seat-reservation-command")
			.seatConfirmationCommandId("seat-confirmation-command")
			.seatCompensationCommandId("seat-compensation-command")
			.paymentLockCommandId("payment-lock-command")
			.paymentCompensationCommandId("payment-compensation-command")
			.workCreationCommandId("work-creation-command")
			.workCompensationCommandId("work-compensation-command")
			.chatCreationCommandId("chat-creation-command")
			.chatCompensationCommandId("chat-compensation-command")
			.leaseToken("lease-token")
			.leaseExpiresAt(LocalDateTime.now().minusMinutes(1))
			.startedAt(LocalDateTime.now().minusMinutes(2))
			.build();
	}

	private void completeTogether(Long jobPostId, CountDownLatch ready, CountDownLatch start) {
		ready.countDown();
		try {
			if (!start.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("모집 완료 동시성 테스트 대기 시간이 초과되었습니다.");
			}
			recruitmentCompletionService.complete(jobPostId, "completion-command");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("모집 완료 동시성 테스트가 중단되었습니다.", exception);
		}
	}

	private ApplicationStatus statusOf(Application application) {
		return applicationRepository.findById(application.getId()).orElseThrow().getStatus();
	}

	private MatchingStatus matchingStatusOf(Matching matching) {
		return matchingRepository.findById(matching.getId()).orElseThrow().getStatus();
	}

	private void deletePersistence() {
		sagaRepository.deleteAll();
		matchingHistoryRepository.deleteAll();
		matchingRepository.deleteAll();
		scoreSnapshotRepository.deleteAll();
		scoreBatchRepository.deleteAll();
		outboxEventRepository.deleteAll();
		applicationHistoryRepository.deleteAll();
		applicationRepository.deleteAll();
	}
}
