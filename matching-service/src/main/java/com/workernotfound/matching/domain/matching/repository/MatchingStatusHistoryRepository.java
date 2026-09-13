package com.workernotfound.matching.domain.matching.repository;

import com.workernotfound.matching.domain.matching.entity.MatchingStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchingStatusHistoryRepository extends JpaRepository<MatchingStatusHistory, Long> {
}
