package com.workernotfound.job.domain.job.repository;

import com.workernotfound.job.domain.job.entity.JobPost;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobPostRepository
        extends JpaRepository<JobPost, Long> {
}