package com.workernotfound.matching.domain.score.service;

import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.score.entity.MatchingScoreBatch;
import com.workernotfound.matching.domain.score.model.MatchingScoreQueueProjection;
import com.workernotfound.matching.domain.score.entity.MatchingScoreSnapshot;
import com.workernotfound.matching.domain.score.policy.TrustWeightedScorePolicy;
import com.workernotfound.matching.domain.score.repository.MemberTrustScoreRepository;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MatchingScoreQueuePreparationService {

	private final ApplicationRepository applicationRepository;
	private final MatchingScoreCalculationService calculationService;
	private final MatchingScoreFindService findService;
	private final MemberTrustScoreRepository trustScoreRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Optional<MatchingScoreQueueProjection> prepare(Long jobPostId) {
		List<Long> activeApplicationIds = applicationRepository.findAppliedIdsByJobPostId(jobPostId);
		if (activeApplicationIds.isEmpty()) {
			return Optional.empty();
		}

		MatchingScoreBatch batch = currentBatch(jobPostId, activeApplicationIds);
		return Optional.of(toProjection(batch));
	}

	private MatchingScoreBatch currentBatch(
		Long jobPostId,
		List<Long> activeApplicationIds
	) {
		Optional<MatchingScoreBatch> latestBatch = findService.findLatestReadyBatch(jobPostId);
		if (latestBatch.isPresent() && isCurrent(latestBatch.get(), activeApplicationIds)) {
			return latestBatch.get();
		}
		return calculationService.calculate(jobPostId).orElseThrow();
	}

	private boolean isCurrent(MatchingScoreBatch batch, List<Long> activeApplicationIds) {
		if (!TrustWeightedScorePolicy.VERSION.equals(batch.getPolicyVersion())) {
			return false;
		}
		Set<Long> scoredApplicationIds = new HashSet<>(
			findService.findReadyApplicationIds(batch.getId())
		);
		return new HashSet<>(activeApplicationIds).equals(scoredApplicationIds)
			&& hasCurrentTrustScores(batch);
	}

	// 지원 구성이 같아도 지원자의 신뢰 점수가 바뀌었으면 새 묶음으로 다시 계산한다.
	private boolean hasCurrentTrustScores(MatchingScoreBatch batch) {
		List<MatchingScoreSnapshot> snapshots = findService.findRankedSnapshots(batch.getId());
		Map<Long, BigDecimal> currentTrustScores = trustScoreRepository.findTrustScores(
			snapshots.stream().map(snapshot -> snapshot.getApplication().getWorkerMemberId()).toList()
		);
		return snapshots.stream().allMatch(snapshot -> isSameScore(
			snapshot.getRatingScore(),
			currentTrustScores.get(snapshot.getApplication().getWorkerMemberId())
		));
	}

	private boolean isSameScore(BigDecimal scored, BigDecimal current) {
		if (scored == null || current == null) {
			return scored == null && current == null;
		}
		return scored.compareTo(current) == 0;
	}

	private MatchingScoreQueueProjection toProjection(MatchingScoreBatch batch) {
		return new MatchingScoreQueueProjection(
			batch.getJobPostId(),
			batch.getId(),
			batch.getPolicyVersion(),
			findService.findRankedScores(batch.getId())
		);
	}
}
