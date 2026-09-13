package com.workernotfound.matching.domain.application.service;

import com.workernotfound.matching.domain.application.dto.response.OwnerApplicantListResponse;
import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.exception.ApplicationErrorCode;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.score.entity.MatchingScoreBatch;
import com.workernotfound.matching.domain.score.service.MatchingScoreCalculationService;
import com.workernotfound.matching.external.client.member.MemberServiceClient;
import com.workernotfound.matching.external.client.member.dto.WorkerSummaryResponse;
import com.workernotfound.matching.global.exception.BusinessException;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@Transactional
class OwnerApplicantApplicationServiceTests extends IntegrationTestSupport {

	private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 9, 14, 9, 0);

	@Autowired
	private OwnerApplicantApplicationService ownerApplicantApplicationService;

	@Autowired
	private MatchingScoreCalculationService scoreCalculationService;

	@Autowired
	private ApplicationRepository applicationRepository;

	@MockitoBean
	private MemberServiceClient memberServiceClient;

	@Test
	void keepsRequestedBatchAndAppendsNewUnscoredApplicant() {
		Application first = saveApplication(20L, 100L, 30L, BASE_TIME);
		Application second = saveApplication(21L, 100L, 31L, BASE_TIME.plusMinutes(1));
		saveApplication(40L, 200L, 32L, BASE_TIME.plusMinutes(2));
		MatchingScoreBatch fixedBatch = scoreCalculationService.calculate(10L).orElseThrow();
		Application later = saveApplication(22L, 100L, 33L, BASE_TIME.plusMinutes(3));
		scoreCalculationService.calculate(10L).orElseThrow();
		when(memberServiceClient.getWorkerSummaries(anyList())).thenReturn(List.of(
			new WorkerSummaryResponse(20L, "첫 지원자"),
			new WorkerSummaryResponse(21L, "두 번째 지원자")
		));

		OwnerApplicantListResponse response = ownerApplicantApplicationService.getApplicants(
			10L,
			100L,
			fixedBatch.getId(),
			0,
			20
		);

		assertThat(response.scoreBatchId()).isEqualTo(fixedBatch.getId());
		assertThat(response.totalCount()).isEqualTo(3);
		assertThat(response.applicants())
			.extracting(applicant -> applicant.applicationId())
			.containsExactly(first.getId(), second.getId(), later.getId());
		assertThat(response.applicants().get(0).priorityRank()).isEqualTo(1L);
		assertThat(response.applicants().get(0).workerName()).isEqualTo("첫 지원자");
		assertThat(response.applicants().get(0).ratingScore()).isNull();
		assertThat(response.applicants().get(0).missingInputs()).contains("RATING");
		assertThat(response.applicants().get(2).scoreCalculationStatus()).isEqualTo("UNSCORED");
		assertThat(response.applicants().get(2).priorityRank()).isNull();
		assertThat(response.applicants().get(2).profileAvailable()).isFalse();
	}

	@Test
	void rejectsApplicantOwnedByAnotherOwner() {
		Application application = saveApplication(20L, 100L, 30L, BASE_TIME);

		assertThatThrownBy(() -> ownerApplicantApplicationService.getApplicant(
			10L,
			application.getId(),
			200L,
			null
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.getErrorCode()).isEqualTo(ApplicationErrorCode.APPLICATION_FORBIDDEN));
	}

	private Application saveApplication(
		Long workerMemberId,
		Long ownerMemberId,
		Long admissionId,
		LocalDateTime appliedAt
	) {
		return applicationRepository.saveAndFlush(Application.builder()
			.jobPostId(10L)
			.workerMemberId(workerMemberId)
			.ownerMemberId(ownerMemberId)
			.jobApplicationAdmissionId(admissionId)
			.appliedAt(appliedAt)
			.build());
	}
}
