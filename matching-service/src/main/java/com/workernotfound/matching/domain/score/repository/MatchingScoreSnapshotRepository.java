package com.workernotfound.matching.domain.score.repository;

import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.domain.score.entity.MatchingScoreSnapshot;
import com.workernotfound.matching.domain.score.entity.enums.ScoreBatchStatus;
import com.workernotfound.matching.domain.score.entity.enums.ScoreCalculationStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MatchingScoreSnapshotRepository extends JpaRepository<MatchingScoreSnapshot, Long> {

	@Query("""
		select snapshot
		from MatchingScoreSnapshot snapshot
		join fetch snapshot.application application
		where snapshot.scoreBatch.id = :scoreBatchId
			and snapshot.scoreBatch.status = :batchStatus
			and snapshot.calculationStatus = :calculationStatus
			and application.status = :applicationStatus
		order by snapshot.totalScore desc, application.appliedAt asc, application.id asc
		""")
	List<MatchingScoreSnapshot> findRankedByScoreBatchId(
		Long scoreBatchId,
		ScoreBatchStatus batchStatus,
		ScoreCalculationStatus calculationStatus,
		ApplicationStatus applicationStatus
	);
}
