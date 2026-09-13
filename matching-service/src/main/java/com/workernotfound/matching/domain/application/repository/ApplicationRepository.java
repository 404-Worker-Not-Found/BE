package com.workernotfound.matching.domain.application.repository;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.domain.application.model.OwnerApplicantRow;
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

	boolean existsByJobPostId(Long jobPostId);

	boolean existsByJobPostIdAndOwnerMemberId(Long jobPostId, Long ownerMemberId);

	Optional<Application> findByIdAndJobPostIdAndOwnerMemberId(
		Long id,
		Long jobPostId,
		Long ownerMemberId
	);

	@Query(
		value = """
			select new com.workernotfound.matching.domain.application.model.OwnerApplicantRow(
				application,
				snapshot
			)
			from Application application
			left join MatchingScoreSnapshot snapshot
				on snapshot.application = application
				and snapshot.scoreBatch.id = :scoreBatchId
			where application.jobPostId = :jobPostId
				and application.ownerMemberId = :ownerMemberId
				and application.status = :applicationStatus
			order by
				case when snapshot.calculationStatus = com.workernotfound.matching.domain.score.entity.enums.ScoreCalculationStatus.READY
					then 0 else 1 end,
				snapshot.totalScore desc,
				application.appliedAt asc,
				application.id asc
			""",
		countQuery = """
			select count(application)
			from Application application
			where application.jobPostId = :jobPostId
				and application.ownerMemberId = :ownerMemberId
				and application.status = :applicationStatus
			"""
	)
	Page<OwnerApplicantRow> findOwnerApplicantRows(
		Long jobPostId,
		Long ownerMemberId,
		Long scoreBatchId,
		ApplicationStatus applicationStatus,
		Pageable pageable
	);

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

	List<Application> findByJobPostIdAndStatusOrderByAppliedAtAscIdAsc(
		Long jobPostId,
		ApplicationStatus status
	);
}
