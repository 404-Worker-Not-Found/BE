package com.workernotfound.job.domain.job.repository;

import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobPaymentOrderCommandRepository extends JpaRepository<JobPaymentOrderCommand, Long> {

    List<JobPaymentOrderCommand> findByJobPostIdOrderByIssueSequenceAsc(Long jobPostId);

    Optional<JobPaymentOrderCommand> findFirstByJobPostIdOrderByIssueSequenceDesc(Long jobPostId);

    @Query("select max(c.issueSequence) from JobPaymentOrderCommand c where c.jobPostId = :jobPostId")
    Optional<Integer> findMaxIssueSequenceByJobPostId(@Param("jobPostId") Long jobPostId);
}
