package com.workernotfound.matching.domain.matching;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.application.repository.ApplicationStatusHistoryRepository;
import com.workernotfound.matching.domain.application.service.ApplicationCommandService;
import com.workernotfound.matching.domain.matching.entity.Matching;
import com.workernotfound.matching.domain.matching.entity.MatchingStatusHistory;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingActorType;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingSelectionType;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingStatus;
import com.workernotfound.matching.domain.matching.exception.MatchingErrorCode;
import com.workernotfound.matching.domain.matching.repository.MatchingRepository;
import com.workernotfound.matching.domain.matching.repository.MatchingStatusHistoryRepository;
import com.workernotfound.matching.domain.matching.service.AutoMatchingScheduler;
import com.workernotfound.matching.domain.matching.service.AutoMatchingService;
import com.workernotfound.matching.domain.matching.service.AutoMatchingService.AutoMatchingResult;
import com.workernotfound.matching.domain.matching.service.MatchingCommandService;
import com.workernotfound.matching.domain.matching.service.MatchingConfirmationCommandService;
import com.workernotfound.matching.domain.matching.service.MatchingDeclineService;
import com.fasterxml.jackson.databind.JsonNode;
import com.workernotfound.matching.domain.outbox.entity.OutboxEvent;
import com.workernotfound.matching.domain.outbox.repository.OutboxEventRepository;
import com.workernotfound.matching.domain.score.repository.MatchingScoreBatchRepository;
import com.workernotfound.matching.domain.score.repository.MatchingScoreSnapshotRepository;
import com.workernotfound.matching.global.exception.BusinessException;
import com.workernotfound.matching.support.IntegrationTestSupport;
import com.workernotfound.matching.support.TestSchedule;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

// 지원 마감이 지나면 남은 자리만큼 점수 상위 후보에게 제안하고, 거절·만료 뒤에는 다음 후보에게 폴백 제안한다.
class AutoMatchingTests extends IntegrationTestSupport {

	private static final Long JOB_POST_ID = 7_700L;
	private static final Long OWNER_ID = 7_701L;

	@Autowired
	private ApplicationCommandService applicationCommandService;

	@Autowired
	private AutoMatchingService autoMatchingService;

	@Autowired
	private AutoMatchingScheduler scheduler;

	@Autowired
	private MatchingCommandService matchingCommandService;

	@Autowired
	private MatchingDeclineService declineService;

	@Autowired
	private MatchingConfirmationCommandService confirmationCommandService;

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

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

	private long nextAdmissionId = 77_000L;

	@BeforeEach
	@AfterEach
	void cleanUp() {
		jdbc.update("delete from matching_status_histories");
		jdbc.update("delete from matching_confirmation_sagas");
		jdbc.update("delete from matchings");
		jdbc.update("delete from matching_candidate_exhaustions");
		scoreSnapshotRepository.deleteAll();
		scoreBatchRepository.deleteAll();
		outboxEventRepository.deleteAll();
		applicationHistoryRepository.deleteAll();
		applicationRepository.deleteAll();
		jdbc.update("delete from member_trust_scores");
		try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
			connection.serverCommands().flushDb();
		}
	}

	@Test
	void proposesTopScoredCandidatesForRemainingSeatsAfterDeadline() {
		LocalDateTime now = TestSchedule.now();
		Application early = apply(7_801L, now.minusHours(3), now.minusMinutes(1), 2);
		Application middle = apply(7_802L, now.minusHours(2), now.minusMinutes(1), 2);
		Application late = apply(7_803L, now.minusHours(1), now.minusMinutes(1), 2);

		assertThat(autoMatchingService.proposeFor(JOB_POST_ID)).isEqualTo(AutoMatchingResult.PROPOSED);

		List<Matching> proposals = pending();
		assertThat(proposals).extracting(matching -> matching.getApplication().getId())
			.containsExactlyInAnyOrder(early.getId(), middle.getId())
			.doesNotContain(late.getId());
		assertThat(proposals).allSatisfy(matching -> {
			assertThat(matching.getSelectionType()).isEqualTo(MatchingSelectionType.AUTOMATIC);
			assertThat(matching.getScoreSnapshot()).isNotNull();
			assertThat(matching.getExpiresAt()).isCloseTo(TestSchedule.now().plusMinutes(30), within(1, ChronoUnit.MINUTES));
		});
		MatchingStatusHistory history = matchingHistoryRepository.findAll().get(0);
		assertThat(history.getActorType()).isEqualTo(MatchingActorType.SYSTEM);
		assertThat(history.getActorMemberId()).isNull();
		assertThat(history.getReasonCode()).isEqualTo("AUTO_CANDIDATE_PROPOSED");
		// 자리를 모두 채운 뒤에는 더 제안하지 않는다.
		assertThat(autoMatchingService.proposeFor(JOB_POST_ID)).isEqualTo(AutoMatchingResult.NO_SEAT);
		assertThat(matchingRepository.count()).isEqualTo(2);
	}

	@Test
	void trustScoreCanOutrankEarlierApplication() {
		// 먼저 지원(지원 시각 100)·신뢰 0 → 50, 늦게 지원(지원 시각 50)·신뢰 100 → 75.
		insertTrustScore(7_801L, "0.00");
		insertTrustScore(7_802L, "100.00");
		LocalDateTime now = TestSchedule.now();
		apply(7_801L, now.minusHours(3), now.minusMinutes(1), 1);
		Application trusted = apply(7_802L, now.minusHours(2), now.minusMinutes(1), 1);

		autoMatchingService.proposeFor(JOB_POST_ID);

		assertThat(pending()).singleElement()
			.satisfies(matching -> assertThat(matching.getApplication().getId()).isEqualTo(trusted.getId()));
	}

	@Test
	void doesNothingBeforeDeadlineOrWithoutRecruitmentSnapshot() {
		LocalDateTime now = TestSchedule.now();
		apply(7_801L, now.minusHours(1), now.plusMinutes(10), 1);

		assertThat(autoMatchingService.proposeFor(JOB_POST_ID)).isEqualTo(AutoMatchingResult.NOT_DUE);
		applicationCommandService.create(JOB_POST_ID + 1, 7_802L, OWNER_ID, nextAdmissionId++, 1L, now.minusHours(1),
			now.plusDays(1), "legacy-application");
		assertThat(autoMatchingService.proposeFor(JOB_POST_ID + 1)).isEqualTo(AutoMatchingResult.NOT_DUE);
		assertThat(matchingRepository.count()).isZero();
	}

	@Test
	void ownerSelectedPendingCandidateKeepsItsSeat() {
		LocalDateTime now = TestSchedule.now();
		Application manual = apply(7_801L, now.minusHours(3), now.plusMinutes(10), 2);
		Application automatic = apply(7_802L, now.minusHours(2), now.plusMinutes(10), 2);
		apply(7_803L, now.minusHours(1), now.plusMinutes(10), 2);
		matchingCommandService.createManual(JOB_POST_ID, manual.getId(), OWNER_ID, null);
		jdbc.update("update applications set application_deadline = ? where job_post_id = ?", now.minusMinutes(1), JOB_POST_ID);

		autoMatchingService.proposeFor(JOB_POST_ID);

		assertThat(pending()).extracting(matching -> matching.getApplication().getId())
			.containsExactlyInAnyOrder(manual.getId(), automatic.getId());
	}

	@Test
	void expiredAndDeclinedProposalsFallBackToNextCandidates() {
		LocalDateTime now = TestSchedule.now();
		Application first = apply(7_801L, now.minusHours(3), now.minusMinutes(1), 1);
		Application second = apply(7_802L, now.minusHours(2), now.minusMinutes(1), 1);
		Application third = apply(7_803L, now.minusHours(1), now.minusMinutes(1), 1);
		autoMatchingService.proposeFor(JOB_POST_ID);
		Matching firstProposal = matchingRepository.findByApplicationId(first.getId()).orElseThrow();
		jdbc.update("update matchings set expires_at = ? where id = ?", now.minusSeconds(1), firstProposal.getId());

		scheduler.run();

		assertThat(matchingRepository.findById(firstProposal.getId()).orElseThrow().getStatus())
			.isEqualTo(MatchingStatus.EXPIRED);
		Matching secondProposal = matchingRepository.findByApplicationId(second.getId()).orElseThrow();
		assertThat(secondProposal.getSelectionType()).isEqualTo(MatchingSelectionType.FALLBACK);
		assertThat(secondProposal.getStatus()).isEqualTo(MatchingStatus.PENDING);
		assertThat(matchingHistoryRepository.findAll()).anySatisfy(history -> {
			assertThat(history.getToStatus()).isEqualTo(MatchingStatus.EXPIRED);
			assertThat(history.getActorType()).isEqualTo(MatchingActorType.SYSTEM);
			assertThat(history.getReasonCode()).isEqualTo("MATCHING_RESPONSE_EXPIRED");
		});

		declineService.decline(secondProposal.getId(), 7_802L);
		autoMatchingService.proposeFor(JOB_POST_ID);

		Matching thirdProposal = matchingRepository.findByApplicationId(third.getId()).orElseThrow();
		assertThat(thirdProposal.getSelectionType()).isEqualTo(MatchingSelectionType.FALLBACK);
		assertThat(pending()).singleElement()
			.satisfies(matching -> assertThat(matching.getId()).isEqualTo(thirdProposal.getId()));
	}

	@Test
	void recordsExhaustionOnceWhenNoCandidateRemains() {
		LocalDateTime now = TestSchedule.now();
		apply(7_801L, now.minusHours(3), now.minusMinutes(1), 2);
		autoMatchingService.proposeFor(JOB_POST_ID);
		Matching proposal = pending().get(0);

		assertThat(autoMatchingService.proposeFor(JOB_POST_ID)).isEqualTo(AutoMatchingResult.WAITING_FOR_RESPONSES);
		declineService.decline(proposal.getId(), 7_801L);

		assertThat(autoMatchingService.proposeFor(JOB_POST_ID)).isEqualTo(AutoMatchingResult.EXHAUSTED);
		assertThat(autoMatchingService.proposeFor(JOB_POST_ID)).isEqualTo(AutoMatchingResult.NOT_DUE);
		assertThat(jdbc.queryForMap("select owner_member_id, remaining_seats from matching_candidate_exhaustions"
			+ " where job_post_id = ?", JOB_POST_ID))
			.containsEntry("owner_member_id", OWNER_ID)
			.containsEntry("remaining_seats", 2);
	}

	@Test
	void shortensResponseWindowNearWorkStartAndStopsWhenNoTimeRemains() {
		LocalDateTime now = TestSchedule.now();
		apply(7_801L, now.minusHours(3), now.minusMinutes(1), 1, now.plusMinutes(80));

		autoMatchingService.proposeFor(JOB_POST_ID);

		assertThat(pending().get(0).getExpiresAt())
			.isCloseTo(now.plusMinutes(20), within(1, ChronoUnit.MINUTES));

		cleanUp();
		apply(7_801L, now.minusHours(3), now.minusMinutes(1), 1, now.plusMinutes(50));
		assertThat(autoMatchingService.proposeFor(JOB_POST_ID)).isEqualTo(AutoMatchingResult.NOT_DUE);
		assertThat(matchingRepository.count()).isZero();
	}

	@Test
	void expiredProposalCannotBeAcceptedButOpenProposalCan() {
		LocalDateTime now = TestSchedule.now();
		apply(7_801L, now.minusHours(3), now.minusMinutes(1), 1);
		autoMatchingService.proposeFor(JOB_POST_ID);
		Matching proposal = pending().get(0);
		jdbc.update("update matchings set expires_at = ? where id = ?", now.minusSeconds(1), proposal.getId());

		assertThatThrownBy(() -> confirmationCommandService.prepare(proposal.getId(), 7_801L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.getErrorCode()).isEqualTo(MatchingErrorCode.MATCHING_RESPONSE_EXPIRED));

		jdbc.update("update matchings set expires_at = ? where id = ?", now.plus(Duration.ofMinutes(5)), proposal.getId());
		assertThat(confirmationCommandService.prepare(proposal.getId(), 7_801L).sagaId()).isNotNull();
	}

	@Test
	void concurrentRunsDoNotProposeMoreThanRemainingSeats() throws Exception {
		LocalDateTime now = TestSchedule.now();
		apply(7_801L, now.minusHours(3), now.minusMinutes(1), 1);
		apply(7_802L, now.minusHours(2), now.minusMinutes(1), 1);
		apply(7_803L, now.minusHours(1), now.minusMinutes(1), 1);
		java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(4);
		try {
			List<java.util.concurrent.Future<AutoMatchingResult>> runs = new java.util.ArrayList<>();
			for (int index = 0; index < 4; index++) {
				runs.add(executor.submit(() -> {
					start.await();
					return autoMatchingService.proposeFor(JOB_POST_ID);
				}));
			}
			start.countDown();
			for (var run : runs) run.get(30, java.util.concurrent.TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}

		assertThat(pending()).hasSize(1);
		assertThat(matchingRepository.count()).isEqualTo(1);
	}

	@Test
	void proposalsAndExhaustionAppendNotificationEvents() throws Exception {
		LocalDateTime now = TestSchedule.now();
		Application manual = apply(7_801L, now.minusHours(3), now.plusMinutes(10), 2);
		Application automatic = apply(7_802L, now.minusHours(2), now.plusMinutes(10), 2);
		matchingCommandService.createManual(JOB_POST_ID, manual.getId(), OWNER_ID, null);
		jdbc.update("update applications set application_deadline = ? where job_post_id = ?", now.minusMinutes(1), JOB_POST_ID);
		autoMatchingService.proposeFor(JOB_POST_ID);

		List<OutboxEvent> proposals = events("MatchingProposed");
		assertThat(proposals).hasSize(2).allSatisfy(event -> {
			assertThat(event.getAggregateType()).isEqualTo("MATCHING");
			assertThat(event.getRevision()).isEqualTo(1L);
		});
		JsonNode automaticPayload = payloadFor(proposals, automatic.getId());
		assertThat(automaticPayload.path("selectionType").asText()).isEqualTo("AUTOMATIC");
		assertThat(automaticPayload.path("workerMemberId").asLong()).isEqualTo(7_802L);
		assertThat(automaticPayload.path("ownerMemberId").asLong()).isEqualTo(OWNER_ID);
		assertThat(automaticPayload.path("expiresAt").isNull()).isFalse();
		JsonNode manualPayload = payloadFor(proposals, manual.getId());
		assertThat(manualPayload.path("selectionType").asText()).isEqualTo("MANUAL");
		assertThat(manualPayload.path("expiresAt").isNull()).isTrue();

		for (Matching proposal : pending()) declineService.decline(proposal.getId(), proposal.getWorkerMemberId());
		autoMatchingService.proposeFor(JOB_POST_ID);
		autoMatchingService.proposeFor(JOB_POST_ID);

		assertThat(events("MatchingCandidatesExhausted")).singleElement().satisfies(event -> {
			assertThat(event.getAggregateType()).isEqualTo("JOB_POST");
			assertThat(event.getAggregateId()).isEqualTo(JOB_POST_ID);
			JsonNode payload = readPayload(event);
			assertThat(payload.path("ownerMemberId").asLong()).isEqualTo(OWNER_ID);
			assertThat(payload.path("remainingSeats").asInt()).isEqualTo(2);
		});
	}

	private List<OutboxEvent> events(String eventType) {
		return outboxEventRepository.findAll().stream().filter(event -> eventType.equals(event.getEventType())).toList();
	}

	private JsonNode payloadFor(List<OutboxEvent> events, Long applicationId) {
		return events.stream().map(this::readPayload)
			.filter(payload -> payload.path("applicationId").asLong() == applicationId)
			.findFirst().orElseThrow();
	}

	private JsonNode readPayload(OutboxEvent event) {
		try {
			return objectMapper.readTree(event.getPayload());
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private Application apply(Long workerId, LocalDateTime appliedAt, LocalDateTime deadline, int recruitCount) {
		return apply(workerId, appliedAt, deadline, recruitCount, TestSchedule.now().plusDays(1));
	}

	private Application apply(
		Long workerId,
		LocalDateTime appliedAt,
		LocalDateTime deadline,
		int recruitCount,
		LocalDateTime workStartsAt
	) {
		long admissionId = nextAdmissionId++;
		return applicationCommandService.create(JOB_POST_ID, workerId, OWNER_ID, admissionId, 1L, appliedAt,
			workStartsAt, deadline, recruitCount, "auto-matching-" + admissionId);
	}

	private void insertTrustScore(Long memberId, String trustScore) {
		jdbc.update("insert into member_trust_scores(member_id, trust_score, review_count, policy_version, revision,"
			+ " event_id, received_at) values (?, ?, 3, 'review-rating-v1', 1, ?, now(6))",
			memberId, new java.math.BigDecimal(trustScore), "trust-" + memberId);
	}

	private List<Matching> pending() {
		return matchingRepository.findAll().stream()
			.filter(matching -> matching.getStatus() == MatchingStatus.PENDING)
			.toList();
	}
}
