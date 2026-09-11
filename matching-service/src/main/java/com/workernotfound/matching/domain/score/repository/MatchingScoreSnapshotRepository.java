package com.workernotfound.matching.domain.score.repository;

import com.workernotfound.matching.domain.score.entity.MatchingScoreSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchingScoreSnapshotRepository extends JpaRepository<MatchingScoreSnapshot, Long> {
}
