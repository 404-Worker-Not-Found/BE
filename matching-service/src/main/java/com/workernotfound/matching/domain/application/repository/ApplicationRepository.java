package com.workernotfound.matching.domain.application.repository;

import com.workernotfound.matching.domain.application.entity.Application;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

	Optional<Application> findByJobPostIdAndWorkerMemberId(Long jobPostId, Long workerMemberId);

	Optional<Application> findByJobApplicationAdmissionId(Long jobApplicationAdmissionId);

	Page<Application> findByWorkerMemberId(Long workerMemberId, Pageable pageable);

	@Query("select distinct application.jobPostId from Application application")
	List<Long> findDistinctJobPostIds();

	@Query("""
		select application.id
		from Application application
		where application.jobPostId = :jobPostId
			and application.status = com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus.APPLIED
		order by application.appliedAt asc, application.id asc
		""")
	List<Long> findAppliedIdsByJobPostId(Long jobPostId);
}
