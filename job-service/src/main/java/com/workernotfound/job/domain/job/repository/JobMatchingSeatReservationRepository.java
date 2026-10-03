package com.workernotfound.job.domain.job.repository;

import com.workernotfound.job.domain.job.entity.JobMatchingSeatReservation;
import com.workernotfound.job.domain.job.entity.enums.MatchingSeatReservationStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobMatchingSeatReservationRepository extends JpaRepository<JobMatchingSeatReservation, Long> {

    Optional<JobMatchingSeatReservation> findByIdempotencyKey(String idempotencyKey);

    Optional<JobMatchingSeatReservation> findByConfirmIdempotencyKey(String confirmIdempotencyKey);

    Optional<JobMatchingSeatReservation> findByReleaseIdempotencyKey(String releaseIdempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from JobMatchingSeatReservation r where r.id = :id")
    Optional<JobMatchingSeatReservation> findByIdForUpdate(@Param("id") Long id);

    long countByJobPostIdAndStatusIn(Long jobPostId, Collection<MatchingSeatReservationStatus> statuses);

    @Query("""
            select count(r) > 0 from JobMatchingSeatReservation r
            where r.jobPostId = :jobPostId
              and r.status in :statuses
              and (r.matchingId = :matchingId or r.applicationId = :applicationId)
            """)
    boolean existsByJobPostIdAndStatusInAndMatchingOrApplication(
            @Param("jobPostId") Long jobPostId,
            @Param("statuses") Collection<MatchingSeatReservationStatus> statuses,
            @Param("matchingId") Long matchingId,
            @Param("applicationId") Long applicationId
    );

    @Query("""
            select r.id from JobMatchingSeatReservation r
            where r.jobPostId = :jobPostId
              and r.status = com.workernotfound.job.domain.job.entity.enums.MatchingSeatReservationStatus.RESERVED
              and r.expiresAt <= :now
            """)
    List<Long> findOverdueIdsByJobPostId(@Param("jobPostId") Long jobPostId, @Param("now") LocalDateTime now);

    // 공고 행 잠금을 잡은 트랜잭션에서만 호출한다. 기본 키로만 갱신해 보조 인덱스 범위(gap) 잠금을 만들지 않는다.
    // 상태 조건이 있어 이미 회수·확정된 예약은 다시 바꾸지 않는다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update JobMatchingSeatReservation r
            set r.status = com.workernotfound.job.domain.job.entity.enums.MatchingSeatReservationStatus.EXPIRED,
                r.expiredAt = :now,
                r.updatedAt = :now
            where r.id in :ids
              and r.status = com.workernotfound.job.domain.job.entity.enums.MatchingSeatReservationStatus.RESERVED
              and r.expiresAt <= :now
            """)
    int expireByIdIn(@Param("ids") Collection<Long> ids, @Param("now") LocalDateTime now);

    @Query("""
            select distinct r.jobPostId from JobMatchingSeatReservation r
            where r.status = com.workernotfound.job.domain.job.entity.enums.MatchingSeatReservationStatus.RESERVED
              and r.expiresAt <= :now
            order by r.jobPostId
            """)
    List<Long> findJobPostIdsWithOverdueReservation(@Param("now") LocalDateTime now, Pageable pageable);
}
