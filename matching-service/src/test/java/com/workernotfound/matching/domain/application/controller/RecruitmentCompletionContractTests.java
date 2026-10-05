package com.workernotfound.matching.domain.application.controller;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.entity.RecruitmentState;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.application.repository.ApplicationStatusHistoryRepository;
import com.workernotfound.matching.domain.application.repository.RecruitmentStateRepository;
import com.workernotfound.matching.domain.matching.entity.Matching;
import com.workernotfound.matching.domain.matching.entity.MatchingConfirmationSaga;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingSelectionType;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingStatus;
import com.workernotfound.matching.domain.matching.repository.MatchingConfirmationSagaRepository;
import com.workernotfound.matching.domain.matching.repository.MatchingRepository;
import com.workernotfound.matching.domain.matching.repository.MatchingStatusHistoryRepository;
import com.workernotfound.matching.domain.outbox.repository.OutboxEventRepository;
import com.workernotfound.matching.domain.score.repository.MatchingScoreBatchRepository;
import com.workernotfound.matching.domain.score.repository.MatchingScoreSnapshotRepository;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * job-service가 보내는 모집 완료 요청의 수신 계약을 실제 컨트롤러·보안 필터·MySQL로 검증한다.
 *
 * <p>job-service는 마지막 자리 확정 직후 알림을 보내므로, 그 자리를 확정한 Saga가 아직 로컬 확정을 끝내지 않았을 수 있다.
 * 이때는 409로 거절되고, Saga가 끝난 뒤 같은 명령 ID·버전의 재전송이 성공해야 한다.
 */
@AutoConfigureMockMvc
class RecruitmentCompletionContractTests extends IntegrationTestSupport {

	private static final String PATH = "/api/applications/internal/jobs/{jobPostId}/recruitment-completion";
	private static final String INTERNAL_SECRET = "matching-test-internal-secret";
	private static final Long JOB_POST_ID = 30L;
	private static final Long COMPLETED_JOB_VERSION = 2L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TransactionTemplate transactionTemplate;

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
	private RecruitmentStateRepository recruitmentStateRepository;

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
	void rejectsCompletionUntilLastSeatSagaFinishesThenAcceptsSameCommand() throws Exception {
		Application earlierSelected = saveApplication(20L, 1L);
		Matching earlierConfirmed = saveMatching(earlierSelected);
		confirm(earlierSelected.getId(), earlierConfirmed.getId());
		Application lastSeat = saveApplication(21L, 2L);
		Matching lastSeatMatching = saveMatching(lastSeat);
		MatchingConfirmationSaga lastSeatSaga = sagaRepository.saveAndFlush(seatConsumedSaga(lastSeatMatching));
		Application proposed = saveApplication(22L, 3L);
		Matching pendingProposal = saveMatching(proposed);
		Application waiting = saveApplication(23L, 4L);
		String commandId = UUID.randomUUID().toString();

		// 마지막 자리 확정 직후: job-service 자리는 CONSUMED지만 matching-service Saga는 아직 PROCESSING이다.
		complete(commandId)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("APPLICATION-409-006"));

		assertThat(statusOf(lastSeat)).isEqualTo(ApplicationStatus.APPLIED);
		assertThat(statusOf(proposed)).isEqualTo(ApplicationStatus.APPLIED);
		assertThat(statusOf(waiting)).isEqualTo(ApplicationStatus.APPLIED);
		assertThat(matchingStatusOf(pendingProposal)).isEqualTo(MatchingStatus.PENDING);
		assertThat(recruitmentStateRepository.findById(JOB_POST_ID))
			.map(RecruitmentState::getCompletedJobVersion)
			.isEmpty();

		completeSaga(lastSeat.getId(), lastSeatMatching.getId(), lastSeatSaga.getId());

		complete(commandId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.code").value("SUCCESS"));

		assertThat(statusOf(earlierSelected)).isEqualTo(ApplicationStatus.SELECTED);
		assertThat(statusOf(lastSeat)).isEqualTo(ApplicationStatus.SELECTED);
		assertThat(matchingStatusOf(earlierConfirmed)).isEqualTo(MatchingStatus.CONFIRMED);
		assertThat(matchingStatusOf(lastSeatMatching)).isEqualTo(MatchingStatus.CONFIRMED);
		assertThat(statusOf(proposed)).isEqualTo(ApplicationStatus.REJECTED);
		assertThat(statusOf(waiting)).isEqualTo(ApplicationStatus.REJECTED);
		assertThat(matchingStatusOf(pendingProposal)).isEqualTo(MatchingStatus.CANCELED);
		RecruitmentState state = recruitmentStateRepository.findById(JOB_POST_ID).orElseThrow();
		assertThat(state.getCompletedJobVersion()).isEqualTo(COMPLETED_JOB_VERSION);
		assertThat(state.getCompletionCommandId()).isEqualTo(commandId);

		// 응답이 유실되어 같은 명령을 다시 보내도 성공하며 이력·이벤트가 늘지 않는다.
		long applicationHistories = applicationHistoryRepository.count();
		long matchingHistories = matchingHistoryRepository.count();
		long outboxEvents = outboxEventRepository.count();
		complete(commandId).andExpect(status().isOk());
		assertThat(applicationHistoryRepository.count()).isEqualTo(applicationHistories);
		assertThat(matchingHistoryRepository.count()).isEqualTo(matchingHistories);
		assertThat(outboxEventRepository.count()).isEqualTo(outboxEvents);
	}

	private ResultActions complete(String commandId) throws Exception {
		return mockMvc.perform(post(PATH, JOB_POST_ID)
			.header("X-Internal-Secret", INTERNAL_SECRET)
			.header("X-Job-Version", String.valueOf(COMPLETED_JOB_VERSION))
			.header("Idempotency-Key", commandId));
	}

	// Saga 마지막 로컬 트랜잭션처럼 매칭 확정과 지원 선정을 함께 반영한다.
	private void completeSaga(Long applicationId, Long matchingId, Long sagaId) {
		transactionTemplate.executeWithoutResult(status -> {
			LocalDateTime now = LocalDateTime.now();
			applicationRepository.findById(applicationId).orElseThrow().select();
			matchingRepository.findById(matchingId).orElseThrow().confirm(now);
			sagaRepository.findById(sagaId).orElseThrow().complete(now);
		});
	}

	private void confirm(Long applicationId, Long matchingId) {
		transactionTemplate.executeWithoutResult(status -> {
			applicationRepository.findById(applicationId).orElseThrow().select();
			matchingRepository.findById(matchingId).orElseThrow().confirm(LocalDateTime.now());
		});
	}

	private MatchingConfirmationSaga seatConsumedSaga(Matching matching) {
		MatchingConfirmationSaga saga = MatchingConfirmationSaga.builder()
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
			.leaseExpiresAt(LocalDateTime.now().plusMinutes(1))
			.startedAt(LocalDateTime.now().minusMinutes(1))
			.build();
		saga.recordSeat("seat-1", LocalDate.now().plusDays(1), LocalTime.of(9, 0), LocalTime.of(18, 0), false,
			new BigDecimal("90000"), "KRW", null, null);
		saga.recordPayment("payment-1");
		saga.recordWork("work-1");
		saga.recordChatRoom("chat-1");
		saga.consumeSeat();
		return saga;
	}

	private Application saveApplication(Long workerMemberId, Long admissionId) {
		return applicationRepository.saveAndFlush(Application.builder()
			.jobPostId(JOB_POST_ID)
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
		recruitmentStateRepository.deleteAll();
	}
}
