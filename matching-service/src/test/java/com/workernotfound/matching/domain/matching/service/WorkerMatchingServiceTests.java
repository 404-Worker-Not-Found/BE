package com.workernotfound.matching.domain.matching.service;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.application.service.ApplicationCommandService;
import com.workernotfound.matching.domain.matching.dto.response.MatchingListResponse;
import com.workernotfound.matching.domain.matching.dto.response.MatchingResponse;
import com.workernotfound.matching.domain.matching.entity.Matching;
import com.workernotfound.matching.domain.matching.entity.MatchingStatusHistory;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingActorType;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingSelectionType;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingStatus;
import com.workernotfound.matching.domain.matching.exception.MatchingErrorCode;
import com.workernotfound.matching.domain.matching.repository.MatchingRepository;
import com.workernotfound.matching.domain.matching.repository.MatchingStatusHistoryRepository;
import com.workernotfound.matching.global.exception.BusinessException;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class WorkerMatchingServiceTests extends IntegrationTestSupport {

	private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 9, 14, 10, 0);

	@Autowired
	private MatchingApplicationService matchingApplicationService;

	@Autowired
	private MatchingDeclineService matchingDeclineService;

	@Autowired
	private ApplicationCommandService applicationCommandService;

	@Autowired
	private ApplicationRepository applicationRepository;

	@Autowired
	private MatchingRepository matchingRepository;

	@Autowired
	private MatchingStatusHistoryRepository historyRepository;

	@Test
	void listsOnlyOwnedMatchingsInLatestSelectionOrder() {
		Matching older = saveMatching(20L, 30L, BASE_TIME);
		Matching newer = saveMatching(20L, 31L, BASE_TIME.plusMinutes(1));
		saveMatching(21L, 32L, BASE_TIME.plusMinutes(2));

		MatchingListResponse response = matchingApplicationService.getMatchings(20L, 0, 20);

		assertThat(response.totalCount()).isEqualTo(2);
		assertThat(response.matchings())
			.extracting(MatchingResponse::matchingId)
			.containsExactly(newer.getId(), older.getId());
	}

	@Test
	void rejectsMatchingOwnedByAnotherWorker() {
		Matching matching = saveMatching(20L, 30L, BASE_TIME);

		assertThatThrownBy(() -> matchingApplicationService.getMatching(matching.getId(), 21L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.getErrorCode()).isEqualTo(MatchingErrorCode.MATCHING_FORBIDDEN));
	}

	@Test
	void rejectsDeclineByAnotherWorker() {
		Matching matching = saveMatching(20L, 30L, BASE_TIME);

		assertThatThrownBy(() -> matchingDeclineService.decline(matching.getId(), 21L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.getErrorCode()).isEqualTo(MatchingErrorCode.MATCHING_FORBIDDEN));
		assertThat(matching.getStatus()).isEqualTo(MatchingStatus.PENDING);
	}

	@Test
	void declinesPendingMatchingAndKeepsApplicationApplied() {
		Matching matching = saveMatching(20L, 30L, BASE_TIME);

		MatchingResponse response = matchingApplicationService.decline(matching.getId(), 20L);

		assertThat(response.status()).isEqualTo(MatchingStatus.DECLINED.name());
		assertThat(matching.getApplication().getStatus()).isEqualTo(ApplicationStatus.APPLIED);
		assertThat(matching.getRevision()).isEqualTo(2L);
		assertThat(historyRepository.findByMatchingIdOrderByRevisionAsc(matching.getId()))
			.extracting(history -> history.getToStatus())
			.containsExactly(MatchingStatus.PENDING, MatchingStatus.DECLINED);
	}

	@Test
	void repeatedDeclineReturnsExistingResultWithoutAnotherHistory() {
		Matching matching = saveMatching(20L, 30L, BASE_TIME);
		matchingDeclineService.decline(matching.getId(), 20L);

		Matching repeated = matchingDeclineService.decline(matching.getId(), 20L);

		assertThat(repeated.getStatus()).isEqualTo(MatchingStatus.DECLINED);
		assertThat(historyRepository.findByMatchingIdOrderByRevisionAsc(matching.getId())).hasSize(2);
	}

	@Test
	void rejectsDeclineAfterApplicationCancellationCanceledTheMatching() {
		Matching matching = saveMatching(20L, 30L, BASE_TIME);
		applicationCommandService.cancel(matching.getApplication().getId(), 20L, "cancel-command");

		assertThatThrownBy(() -> matchingDeclineService.decline(matching.getId(), 20L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.getErrorCode()).isEqualTo(MatchingErrorCode.MATCHING_STATE_CONFLICT));
	}

	private Matching saveMatching(Long workerMemberId, Long admissionId, LocalDateTime selectedAt) {
		Application application = applicationRepository.saveAndFlush(Application.builder()
			.jobPostId(admissionId)
			.workerMemberId(workerMemberId)
			.ownerMemberId(100L)
			.jobApplicationAdmissionId(admissionId)
			.appliedAt(selectedAt.minusMinutes(10))
			.build());
		Matching matching = matchingRepository.saveAndFlush(Matching.builder()
			.application(application)
			.selectionType(MatchingSelectionType.MANUAL)
			.selectedAt(selectedAt)
			.build());
		historyRepository.saveAndFlush(MatchingStatusHistory.builder()
			.matching(matching)
			.toStatus(MatchingStatus.PENDING)
			.actorType(MatchingActorType.OWNER)
			.actorMemberId(100L)
			.reasonCode("MANUAL_CANDIDATE_SELECTED")
			.revision(1L)
			.changedAt(selectedAt)
			.build());
		return matching;
	}
}
