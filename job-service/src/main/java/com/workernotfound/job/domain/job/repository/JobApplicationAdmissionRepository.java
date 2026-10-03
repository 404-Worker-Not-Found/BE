package com.workernotfound.job.domain.job.repository;

import com.workernotfound.job.domain.job.entity.JobApplicationAdmission;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobApplicationAdmissionRepository extends JpaRepository<JobApplicationAdmission, Long> {

    Optional<JobApplicationAdmission> findByIdempotencyKey(String idempotencyKey);
}
