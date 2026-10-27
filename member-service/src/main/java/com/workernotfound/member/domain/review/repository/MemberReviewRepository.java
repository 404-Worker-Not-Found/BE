package com.workernotfound.member.domain.review.repository;

import com.workernotfound.member.domain.review.entity.MemberReview;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberReviewRepository extends JpaRepository<MemberReview, Long> {

	// 잠금 읽기라 근무 행 잠금을 기다린 사이 다른 트랜잭션이 커밋한 리뷰도 본다. 일반 읽기는 트랜잭션 첫 읽기 시점의 스냅샷을 본다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from MemberReview r where r.workId = :workId and r.reviewerMemberId = :reviewerMemberId")
	Optional<MemberReview> findForUpdate(@Param("workId") Long workId, @Param("reviewerMemberId") Long reviewerMemberId);

	Page<MemberReview> findByRevieweeMemberIdOrderByCreatedAtDescIdDesc(Long revieweeMemberId, Pageable pageable);
}
