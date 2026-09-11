package com.workernotfound.matching.domain.application.repository;

import com.workernotfound.matching.domain.application.entity.Application;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

	Optional<Application> findByJobPostIdAndWorkerMemberId(Long jobPostId, Long workerMemberId);

	Optional<Application> findByJobApplicationAdmissionId(Long jobApplicationAdmissionId);
}
