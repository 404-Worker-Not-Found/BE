package com.workernotfound.matching.domain.application.repository;

import com.workernotfound.matching.domain.application.entity.RecruitmentState;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RecruitmentStateRepository extends JpaRepository<RecruitmentState, Long> {

	@Modifying
	@Query(
		value = """
			insert into recruitment_states (job_post_id)
			values (:jobPostId)
			on duplicate key update job_post_id = values(job_post_id)
			""",
		nativeQuery = true
	)
	void ensureExists(Long jobPostId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select state from RecruitmentState state where state.jobPostId = :jobPostId")
	Optional<RecruitmentState> findForUpdate(Long jobPostId);
}
