package com.workernotfound.job.domain.job.repository;

import com.workernotfound.job.domain.job.entity.JobAdmissionEventReceipt;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobAdmissionEventReceiptRepository extends JpaRepository<JobAdmissionEventReceipt, Long> {

    Optional<JobAdmissionEventReceipt> findByEventId(String eventId);
}
