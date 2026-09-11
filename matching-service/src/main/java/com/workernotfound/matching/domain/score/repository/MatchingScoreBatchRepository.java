package com.workernotfound.matching.domain.score.repository;

import com.workernotfound.matching.domain.score.entity.MatchingScoreBatch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchingScoreBatchRepository extends JpaRepository<MatchingScoreBatch, Long> {
}
