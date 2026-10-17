package com.workernotfound.job.domain.job.repository;

import com.workernotfound.job.domain.job.entity.JobApplicationAdmission;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobApplicationAdmissionRepository extends JpaRepository<JobApplicationAdmission, Long> {

    Optional<JobApplicationAdmission> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from JobApplicationAdmission a where a.id = :id")
    Optional<JobApplicationAdmission> findByIdForUpdate(@Param("id") Long id);

    // 결제 조건을 바꿀 수 있는지 확인한다. 지원 승인이 한 번이라도 발급된 공고는 이미 공개된 적이 있다.
    boolean existsByJobPostId(Long jobPostId);

    // 만료 시각이 지난 RESERVED 승인 중 afterId 다음 ID. 잠그지 않고 후보만 고르며 idx_job_application_admissions_status_expires를 탄다.
    @Query("""
            select a.id from JobApplicationAdmission a
            where a.status = com.workernotfound.job.domain.job.entity.enums.ApplicationAdmissionStatus.RESERVED
              and a.expiresAt <= :now
              and a.id > :afterId
            order by a.id
            """)
    List<Long> findOverdueReservedIdsAfter(
            @Param("now") LocalDateTime now,
            @Param("afterId") Long afterId,
            Pageable pageable
    );
}
