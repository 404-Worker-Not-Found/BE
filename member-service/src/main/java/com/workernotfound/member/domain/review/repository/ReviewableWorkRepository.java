package com.workernotfound.member.domain.review.repository;

import com.workernotfound.member.domain.review.entity.ReviewableWork;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewableWorkRepository extends JpaRepository<ReviewableWork, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select w from ReviewableWork w where w.workId = :workId")
	Optional<ReviewableWork> findByIdForUpdate(@Param("workId") Long workId);

	// 회원이 참여했고 작성 기간 안이며 아직 본인이 리뷰하지 않은 근무.
	@Query(value = """
		select w from ReviewableWork w
		where (w.ownerMemberId = :memberId or w.workerMemberId = :memberId)
		  and w.completedAt > :completedAfter
		  and not exists (select r.id from MemberReview r where r.workId = w.workId and r.reviewerMemberId = :memberId)
		order by w.completedAt desc, w.workId desc
		""", countQuery = """
		select count(w) from ReviewableWork w
		where (w.ownerMemberId = :memberId or w.workerMemberId = :memberId)
		  and w.completedAt > :completedAfter
		  and not exists (select r.id from MemberReview r where r.workId = w.workId and r.reviewerMemberId = :memberId)
		""")
	Page<ReviewableWork> findPending(@Param("memberId") Long memberId,
		@Param("completedAfter") LocalDateTime completedAfter, Pageable pageable);
}
