package com.workernotfound.job.domain.job.repository;

import com.workernotfound.job.domain.job.entity.JobApplicationAdmission;
import jakarta.persistence.LockModeType;
import java.util.Optional;
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
}
