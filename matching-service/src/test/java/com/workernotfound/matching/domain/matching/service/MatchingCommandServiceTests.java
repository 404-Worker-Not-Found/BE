package com.workernotfound.matching.domain.matching.service;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.application.service.ApplicationCommandService;
import com.workernotfound.matching.domain.matching.entity.Matching;
import com.workernotfound.matching.domain.matching.dto.request.CreateManualMatchingRequest;
import com.workernotfound.matching.domain.matching.dto.response.MatchingResponse;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingSelectionType;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingStatus;
import com.workernotfound.matching.domain.matching.exception.MatchingErrorCode;
import com.workernotfound.matching.domain.matching.repository.MatchingRepository;
import com.workernotfound.matching.domain.matching.repository.MatchingStatusHistoryRepository;
import com.workernotfound.matching.domain.score.entity.MatchingScoreBatch;
import com.workernotfound.matching.domain.score.service.MatchingScoreCalculationService;
import com.workernotfound.matching.global.exception.BusinessException;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class MatchingCommandServiceTests extends IntegrationTestSupport {

	private static final Long JOB_POST_ID = 10L;
	private static final Long OWNER_MEMBER_ID = 100L;
	private static final LocalDateTime APPLIED_AT = LocalDateTime.of(2026, 9, 14, 10, 0);

	@Autowired
	private MatchingCommandService matchingCommandService;

	@Autowired
	private MatchingApplicationService matchingApplicationService;

	@Autowired
	private ApplicationCommandService applicationCommandService;

	@Autowired
	private MatchingScoreCalculationService scoreCalculationService;

	@Autowired
	private ApplicationRepository applicationRepository;

	@Autowired
	private MatchingRepository matchingRepository;

	@Autowired
	private MatchingStatusHistoryRepository historyRepository;

	@Test
	void createsPendingManualMatchingWithScoreBasis() {
		Application application = saveApplication(20L, 30L);
		MatchingScoreBatch batch = scoreCalculationService.calculate(JOB_POST_ID).orElseThrow();

		Matching matching = matchingCommandService.createManual(
			JOB_POST_ID,
			application.getId(),
			OWNER_MEMBER_ID,
			batch.getId()
		);

		assertThat(matching.getStatus()).isEqualTo(MatchingStatus.PENDING);
		assertThat(matching.getSelectionType()).isEqualTo(MatchingSelectionType.MANUAL);
		assertThat(matching.getScoreBatch().getId()).isEqualTo(batch.getId());
		assertThat(matching.getScoreSnapshot()).isNotNull();
		assertThat(matching.getExpiresAt()).isNull();
		assertThat(matching.getRevision()).isEqualTo(1L);
		assertThat(historyRepository.count()).isOne();
		assertThat(historyRepository.findAll().get(0).getActorMemberId()).isEqualTo(OWNER_MEMBER_ID);
	}

	@Test
	void mapsCreatedMatchingResponseInsideTransaction() {
		Application application = saveApplication(20L, 30L);
		MatchingScoreBatch batch = scoreCalculationService.calculate(JOB_POST_ID).orElseThrow();

		MatchingResponse response = matchingApplicationService.createManual(
			JOB_POST_ID,
			application.getId(),
			OWNER_MEMBER_ID,
			new CreateManualMatchingRequest(batch.getId())
		);

		assertThat(response.applicationId()).isEqualTo(application.getId());
		assertThat(response.scoreBatchId()).isEqualTo(batch.getId());
		assertThat(response.scoreSnapshotId()).isNotNull();
		assertThat(response.totalScore()).isNotNull();
	}

	@Test
	void createsManualMatchingWithoutFabricatingMissingScore() {
		Application application = saveApplication(20L, 30L);

		Matching matching = matchingCommandService.createManual(
			JOB_POST_ID,
			application.getId(),
			OWNER_MEMBER_ID,
			null
		);

		assertThat(matching.getScoreBatch()).isNull();
		assertThat(matching.getScoreSnapshot()).isNull();
	}

	@Test
	void returnsExistingPendingManualMatchingForRepeatedRequest() {
		Application application = saveApplication(20L, 30L);
		Matching first = matchingCommandService.createManual(
			JOB_POST_ID,
			application.getId(),
			OWNER_MEMBER_ID,
			null
		);

		Matching repeated = matchingCommandService.createManual(
			JOB_POST_ID,
			application.getId(),
			OWNER_MEMBER_ID,
			null
		);

		assertThat(repeated.getId()).isEqualTo(first.getId());
		assertThat(matchingRepository.count()).isOne();
		assertThat(historyRepository.count()).isOne();
	}

	@Test
	void rejectsAnotherOwnerWithoutRevealingApplicationExistence() {
		Application application = saveApplication(20L, 30L);

		assertThatThrownBy(() -> matchingCommandService.createManual(
			JOB_POST_ID,
			application.getId(),
			200L,
			null
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.getErrorCode()).isEqualTo(MatchingErrorCode.MATCHING_FORBIDDEN));
	}

	@Test
	void rejectsCanceledApplication() {
		Application application = saveApplication(20L, 30L);
		application.cancel();
		applicationRepository.flush();

		assertThatThrownBy(() -> matchingCommandService.createManual(
			JOB_POST_ID,
			application.getId(),
			OWNER_MEMBER_ID,
			null
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.getErrorCode()).isEqualTo(MatchingErrorCode.APPLICATION_NOT_SELECTABLE));
	}

	@Test
	void applicationCancellationAlsoCancelsPendingMatching() {
		Application application = saveApplication(20L, 30L);
		Matching matching = matchingCommandService.createManual(
			JOB_POST_ID,
			application.getId(),
			OWNER_MEMBER_ID,
			null
		);

		applicationCommandService.cancel(
			application.getId(),
			application.getWorkerMemberId(),
			"cancel-command"
		);

		assertThat(matching.getStatus()).isEqualTo(MatchingStatus.CANCELED);
		assertThat(historyRepository.count()).isEqualTo(2);
	}

	private Application saveApplication(Long workerMemberId, Long admissionId) {
		return applicationRepository.saveAndFlush(Application.builder()
			.jobPostId(JOB_POST_ID)
			.workerMemberId(workerMemberId)
			.ownerMemberId(OWNER_MEMBER_ID)
			.jobApplicationAdmissionId(admissionId)
			.appliedAt(APPLIED_AT)
			.build());
	}
}
