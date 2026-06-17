package com.workernotfound.job.domain.job.repository;

import com.workernotfound.job.domain.job.entity.JobStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobStatusHistoryRepository
        extends JpaRepository<JobStatusHistory, Long> {
}