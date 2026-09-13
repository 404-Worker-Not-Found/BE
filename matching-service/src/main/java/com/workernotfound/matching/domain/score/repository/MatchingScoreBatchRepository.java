package com.workernotfound.matching.domain.score.repository;

import com.workernotfound.matching.domain.score.entity.MatchingScoreBatch;
import com.workernotfound.matching.domain.score.entity.enums.ScoreBatchStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchingScoreBatchRepository extends JpaRepository<MatchingScoreBatch, Long> {

	Optional<MatchingScoreBatch> findFirstByJobPostIdAndStatusOrderByCompletedAtDescIdDesc(
		Long jobPostId,
		ScoreBatchStatus status
	);
}
