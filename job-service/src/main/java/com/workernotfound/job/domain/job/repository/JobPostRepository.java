package com.workernotfound.job.domain.job.repository;

import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobPostRepository extends JpaRepository<JobPost, Long> {

    List<JobPost> findByStatus(JobStatus status);

    List<JobPost> findByStatusAndCategoryIdIn(JobStatus status, List<Long> categoryIds);
}
