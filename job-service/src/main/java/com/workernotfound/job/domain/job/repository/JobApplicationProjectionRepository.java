package com.workernotfound.job.domain.job.repository;

import com.workernotfound.job.domain.job.entity.JobApplicationProjection;
import com.workernotfound.job.domain.job.entity.enums.ApplicationStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobApplicationProjectionRepository extends JpaRepository<JobApplicationProjection, Long> {

    long countByJobPostIdAndStatus(Long jobPostId, ApplicationStatus status);

    // 페이지에 담긴 공고들의 지원자 수를 한 번에 센다. APPLIED 지원이 없는 공고는 결과에 없다.
    @Query("""
            select p.jobPostId as jobPostId, count(p) as applicantCount
            from JobApplicationProjection p
            where p.jobPostId in :jobPostIds
              and p.status = com.workernotfound.job.domain.job.entity.enums.ApplicationStatus.APPLIED
            group by p.jobPostId
            """)
    List<JobApplicantCount> countAppliedByJobPostIdIn(@Param("jobPostIds") Collection<Long> jobPostIds);
}
