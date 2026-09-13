package com.workernotfound.matching.domain.score.service;

import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.domain.score.entity.MatchingScoreBatch;
import com.workernotfound.matching.domain.score.entity.MatchingScoreSnapshot;
import com.workernotfound.matching.domain.score.entity.enums.ScoreBatchStatus;
import com.workernotfound.matching.domain.score.entity.enums.ScoreCalculationStatus;
import com.workernotfound.matching.domain.score.model.RankedApplicationScore;
import com.workernotfound.matching.domain.score.repository.MatchingScoreBatchRepository;
import com.workernotfound.matching.domain.score.repository.MatchingScoreSnapshotRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MatchingScoreFindService {

	private final MatchingScoreBatchRepository batchRepository;
	private final MatchingScoreSnapshotRepository snapshotRepository;

	public Optional<MatchingScoreBatch> findLatestReadyBatch(Long jobPostId) {
		return batchRepository.findFirstByJobPostIdAndStatusOrderByCompletedAtDescIdDesc(
			jobPostId,
			ScoreBatchStatus.READY
		);
	}

	public List<MatchingScoreSnapshot> findRankedSnapshots(Long scoreBatchId) {
		return snapshotRepository.findRankedByScoreBatchId(
			scoreBatchId,
			ScoreBatchStatus.READY,
			ScoreCalculationStatus.READY,
			ApplicationStatus.APPLIED
		);
	}

	public List<RankedApplicationScore> findRankedScores(Long scoreBatchId) {
		return findRankedSnapshots(scoreBatchId).stream()
			.map(snapshot -> new RankedApplicationScore(
				snapshot.getApplication().getId(),
				snapshot.getTotalScore()
			))
			.toList();
	}
}
