package com.workernotfound.job.domain.job.repository;

import com.workernotfound.job.domain.job.entity.JobApplicantCountState;
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

public interface JobApplicantCountStateRepository extends JpaRepository<JobApplicantCountState, Long> {

    // 이벤트와 스냅샷 반영이 동시에 처음 오는 공고에서도 행 하나만 만든다. 이미 있으면 바꾸지 않는다.
    @Modifying
    @Query(
            value = """
                    insert into job_applicant_count_states (job_post_id, created_at, updated_at)
                    values (:jobPostId, :now, :now)
                    on duplicate key update job_post_id = job_post_id
                    """,
            nativeQuery = true
    )
    void ensureExists(@Param("jobPostId") Long jobPostId, @Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from JobApplicantCountState s where s.jobPostId = :jobPostId")
    Optional<JobApplicantCountState> findByJobPostIdForUpdate(@Param("jobPostId") Long jobPostId);

    boolean existsByJobPostIdAndSyncedAtIsNotNull(Long jobPostId);

    @Query("""
            select s.jobPostId from JobApplicantCountState s
            where s.jobPostId in :jobPostIds
              and s.syncedAt is not null
            """)
    List<Long> findSyncedJobPostIdsByJobPostIdIn(@Param("jobPostIds") Collection<Long> jobPostIds);

    // 초기 적재 전(상태 행 없음 포함)이거나 마지막 비교 뒤 이벤트로 바뀐 공고 중 afterId 다음 ID. 잠그지 않고 후보만 고른다.
    @Query("""
            select p.id from JobPost p
            left join JobApplicantCountState s on s.jobPostId = p.id
            where p.id > :afterId
              and (s.syncedAt is null or s.changedAt > s.syncedAt)
            order by p.id
            """)
    List<Long> findSyncRequiredJobPostIdsByIdAfter(@Param("afterId") Long afterId, Pageable pageable);
}
