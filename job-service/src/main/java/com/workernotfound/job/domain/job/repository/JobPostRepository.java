package com.workernotfound.job.domain.job.repository;

import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface JobPostRepository extends JpaRepository<JobPost, Long> {

    List<JobPost> findByStatus(JobStatus status);

    List<JobPost> findByStatusAndCategoryIdIn(JobStatus status, List<Long> categoryIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from JobPost j where j.id = :id")
    Optional<JobPost> findByIdForUpdate(@Param("id") Long id);
}
