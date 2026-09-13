package com.workernotfound.matching.domain.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.matching.domain.application.dto.response.OwnerApplicantListResponse;
import com.workernotfound.matching.domain.application.dto.response.OwnerApplicantResponse;
import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.exception.ApplicationErrorCode;
import com.workernotfound.matching.domain.application.model.OwnerApplicantRow;
import com.workernotfound.matching.domain.score.entity.MatchingScoreBatch;
import com.workernotfound.matching.domain.score.entity.MatchingScoreSnapshot;
import com.workernotfound.matching.domain.score.entity.enums.ScoreCalculationStatus;
import com.workernotfound.matching.domain.score.service.MatchingScoreFindService;
import com.workernotfound.matching.external.client.member.MemberServiceClient;
import com.workernotfound.matching.external.client.member.MemberServiceClientException;
import com.workernotfound.matching.external.client.member.dto.WorkerSummaryResponse;
import com.workernotfound.matching.global.exception.BusinessException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OwnerApplicantApplicationService {

	private static final TypeReference<List<String>> MISSING_INPUTS_TYPE = new TypeReference<>() {
	};

	private final ApplicationFindService applicationFindService;
	private final MatchingScoreFindService scoreFindService;
	private final MemberServiceClient memberServiceClient;
	private final ObjectMapper objectMapper;

	public OwnerApplicantListResponse getApplicants(
		Long jobPostId,
		Long ownerMemberId,
		Long requestedScoreBatchId,
		int page,
		int size
	) {
		applicationFindService.validateOwnerAccess(jobPostId, ownerMemberId);
		MatchingScoreBatch batch = resolveBatch(jobPostId, requestedScoreBatchId);
		Long scoreBatchId = batch == null ? null : batch.getId();
		Page<OwnerApplicantRow> applicantPage = applicationFindService.findOwnerApplicants(
			jobPostId,
			ownerMemberId,
			scoreBatchId,
			page,
			size
		);
		Map<Long, WorkerSummaryResponse> summaries = findWorkerSummaries(applicantPage.getContent());
		List<OwnerApplicantResponse> applicants = mapApplicants(applicantPage, summaries);
		return toListResponse(batch, applicantPage, applicants);
	}

	public OwnerApplicantResponse getApplicant(
		Long jobPostId,
		Long applicationId,
		Long ownerMemberId,
		Long requestedScoreBatchId
	) {
		Application application = applicationFindService.findOwnerApplicant(
			jobPostId,
			applicationId,
			ownerMemberId
		);
		MatchingScoreBatch batch = resolveBatch(jobPostId, requestedScoreBatchId);
		MatchingScoreSnapshot snapshot = batch == null ? null : scoreFindService
			.findSnapshot(batch.getId(), applicationId)
			.orElse(null);
		String workerName = findWorkerName(application.getWorkerMemberId());
		return toApplicant(application, snapshot, workerName, findPriorityRank(batch, applicationId));
	}

	private MatchingScoreBatch resolveBatch(Long jobPostId, Long requestedScoreBatchId) {
		if (requestedScoreBatchId == null) {
			return scoreFindService.findLatestReadyBatch(jobPostId).orElse(null);
		}
		return scoreFindService.findReadyBatch(requestedScoreBatchId, jobPostId)
			.orElseThrow(() -> new BusinessException(ApplicationErrorCode.SCORE_BATCH_NOT_FOUND));
	}

	private Map<Long, WorkerSummaryResponse> findWorkerSummaries(List<OwnerApplicantRow> rows) {
		List<Long> memberIds = rows.stream()
			.map(row -> row.application().getWorkerMemberId())
			.distinct()
			.toList();
		if (memberIds.isEmpty()) {
			return Map.of();
		}
		try {
			return memberServiceClient.getWorkerSummaries(memberIds).stream()
				.collect(Collectors.toMap(WorkerSummaryResponse::memberId, Function.identity()));
		} catch (MemberServiceClientException exception) {
			throw new BusinessException(ApplicationErrorCode.DEPENDENCY_SERVICE_UNAVAILABLE);
		}
	}

	private String findWorkerName(Long workerMemberId) {
		try {
			return memberServiceClient.getWorkerSummaries(List.of(workerMemberId)).stream()
				.findFirst()
				.map(WorkerSummaryResponse::name)
				.orElse(null);
		} catch (MemberServiceClientException exception) {
			throw new BusinessException(ApplicationErrorCode.DEPENDENCY_SERVICE_UNAVAILABLE);
		}
	}

	private List<OwnerApplicantResponse> mapApplicants(
		Page<OwnerApplicantRow> page,
		Map<Long, WorkerSummaryResponse> summaries
	) {
		long offset = page.getPageable().getOffset();
		return IntStream.range(0, page.getContent().size())
			.mapToObj(index -> {
				OwnerApplicantRow row = page.getContent().get(index);
				WorkerSummaryResponse summary = summaries.get(row.application().getWorkerMemberId());
				Long rank = isReady(row.scoreSnapshot()) ? offset + index + 1 : null;
				return toApplicant(
					row.application(),
					row.scoreSnapshot(),
					summary == null ? null : summary.name(),
					rank
				);
			})
			.toList();
	}

	private OwnerApplicantResponse toApplicant(
		Application application,
		MatchingScoreSnapshot snapshot,
		String workerName,
		Long rank
	) {
		return OwnerApplicantResponse.from(
			application,
			snapshot,
			workerName,
			rank,
			readMissingInputs(snapshot)
		);
	}

	private Long findPriorityRank(MatchingScoreBatch batch, Long applicationId) {
		if (batch == null) {
			return null;
		}
		List<MatchingScoreSnapshot> ranked = scoreFindService.findRankedSnapshots(batch.getId());
		for (int index = 0; index < ranked.size(); index++) {
			if (ranked.get(index).getApplication().getId().equals(applicationId)) {
				return (long) index + 1;
			}
		}
		return null;
	}

	private boolean isReady(MatchingScoreSnapshot snapshot) {
		return snapshot != null && snapshot.getCalculationStatus() == ScoreCalculationStatus.READY;
	}

	private List<String> readMissingInputs(MatchingScoreSnapshot snapshot) {
		if (snapshot == null || snapshot.getMissingInputs() == null) {
			return List.of();
		}
		try {
			return objectMapper.readValue(snapshot.getMissingInputs(), MISSING_INPUTS_TYPE);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("저장된 점수 누락 입력을 읽을 수 없습니다.", exception);
		}
	}

	private OwnerApplicantListResponse toListResponse(
		MatchingScoreBatch batch,
		Page<OwnerApplicantRow> page,
		List<OwnerApplicantResponse> applicants
	) {
		return new OwnerApplicantListResponse(
			batch == null ? null : batch.getId(),
			batch == null ? null : batch.getPolicyVersion(),
			batch == null ? null : batch.getModelVersion(),
			batch == null ? null : batch.getCompletedAt(),
			page.getNumber(),
			page.getSize(),
			page.getTotalElements(),
			page.getTotalPages(),
			applicants
		);
	}
}
