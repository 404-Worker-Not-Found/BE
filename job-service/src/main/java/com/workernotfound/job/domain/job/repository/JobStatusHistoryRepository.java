package com.workernotfound.job.domain.job.repository;

import com.workernotfound.job.domain.job.entity.JobStatusHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobStatusHistoryRepository
        extends JpaRepository<JobStatusHistory, Long> {

    List<JobStatusHistory> findByJobPostIdOrderByIdAsc(Long jobPostId);
}
