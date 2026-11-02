package com.workernotfound.matching.domain.matching.repository;

import com.workernotfound.matching.domain.matching.entity.Matching;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingStatus;
import com.workernotfound.matching.domain.matching.model.MatchingLockTarget;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface MatchingRepository extends JpaRepository<Matching, Long> {

	Optional<Matching> findByApplicationId(Long applicationId);

	// 공고의 모집 자리를 차지한 매칭: 응답 대기 중이거나 한 번이라도 확정된 매칭. 확정 뒤 취소된 근무의 자리는 반환하지 않는다.
	@Query("""
		select count(matching)
		from Matching matching
		where matching.jobPostId = :jobPostId
			and (matching.status = com.workernotfound.matching.domain.matching.entity.enums.MatchingStatus.PENDING
				or matching.confirmedAt is not null)
		""")
	long countOccupiedSeats(Long jobPostId);

	@Query("select matching.application.id from Matching matching where matching.jobPostId = :jobPostId")
	List<Long> findApplicationIdsByJobPostId(Long jobPostId);

	boolean existsByJobPostIdAndStatus(Long jobPostId, MatchingStatus status);

	boolean existsByJobPostIdAndStatusIn(Long jobPostId, List<MatchingStatus> statuses);

	@Query("""
		select matching.id
		from Matching matching
		where matching.status = com.workernotfound.matching.domain.matching.entity.enums.MatchingStatus.PENDING
			and matching.expiresAt <= :now
		order by matching.expiresAt asc, matching.id asc
		""")
	List<Long> findExpiredPendingIds(LocalDateTime now, Pageable pageable);

	boolean existsByApplicationId(Long applicationId);

	@EntityGraph(attributePaths = {"application", "scoreBatch", "scoreSnapshot"})
	Page<Matching> findByWorkerMemberId(Long workerMemberId, Pageable pageable);

	@EntityGraph(attributePaths = {"application", "scoreBatch", "scoreSnapshot"})
	@Query("select matching from Matching matching where matching.id = :matchingId")
	Optional<Matching> findDetailById(Long matchingId);

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
