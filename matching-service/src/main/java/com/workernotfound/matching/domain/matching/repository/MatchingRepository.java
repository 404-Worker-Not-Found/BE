package com.workernotfound.matching.domain.matching.repository;

import com.workernotfound.matching.domain.matching.entity.Matching;
import com.workernotfound.matching.domain.matching.model.MatchingLockTarget;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface MatchingRepository extends JpaRepository<Matching, Long> {

	Optional<Matching> findByApplicationId(Long applicationId);

	boolean existsByApplicationId(Long applicationId);

	@EntityGraph(attributePaths = {"application", "scoreBatch", "scoreSnapshot"})
	Page<Matching> findByWorkerMemberId(Long workerMemberId, Pageable pageable);

	@Query("""
		select new com.workernotfound.matching.domain.matching.model.MatchingLockTarget(
			matching.application.id,
			matching.workerMemberId
		)
		from Matching matching
		where matching.id = :matchingId
		""")
	Optional<MatchingLockTarget> findLockTargetById(Long matchingId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select matching from Matching matching where matching.id = :matchingId")
	Optional<Matching> findByIdForUpdate(Long matchingId);
}
