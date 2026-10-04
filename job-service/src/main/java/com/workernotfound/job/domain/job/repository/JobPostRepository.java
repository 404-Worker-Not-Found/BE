package com.workernotfound.job.domain.job.repository;

import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface JobPostRepository extends JpaRepository<JobPost, Long> {

    List<JobPost> findByStatus(JobStatus status);

    List<JobPost> findByStatusAndCategoryIdIn(JobStatus status, List<Long> categoryIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from JobPost j where j.id = :id")
    Optional<JobPost> findByIdForUpdate(@Param("id") Long id);

    // 확정(CONSUMED) 예약 수가 모집 인원에 도달했는데 아직 모집 중인 공고 중 afterId 다음 ID. 잠그지 않고 ID만 조회한다.
    @Query("""
            select j.id from JobPost j
            where j.status in :statuses
              and j.id > :afterId
              and j.recruitCount <= (
                  select count(r) from JobMatchingSeatReservation r
                  where r.jobPostId = j.id
                    and r.status = com.workernotfound.job.domain.job.entity.enums.MatchingSeatReservationStatus.CONSUMED
              )
            order by j.id
            """)
    List<Long> findFilledIdsByStatusInAfter(
            @Param("statuses") Collection<JobStatus> statuses,
            @Param("afterId") Long afterId,
            Pageable pageable
    );
}
