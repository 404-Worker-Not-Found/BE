package com.workernotfound.matching.domain.matching.repository;

import com.workernotfound.matching.domain.matching.entity.MatchingStatusHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchingStatusHistoryRepository extends JpaRepository<MatchingStatusHistory, Long> {

	List<MatchingStatusHistory> findByMatchingIdOrderByRevisionAsc(Long matchingId);
}
