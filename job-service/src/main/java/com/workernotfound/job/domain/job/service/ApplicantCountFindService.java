package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.enums.ApplicationStatus;
import com.workernotfound.job.domain.job.repository.JobApplicantCount;
import com.workernotfound.job.domain.job.repository.JobApplicantCountStateRepository;
import com.workernotfound.job.domain.job.repository.JobApplicationProjectionRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공고별 지원자 수(현재 APPLIED 지원 수)를 projection에서 읽는다.
 *
 * <p>초기 적재가 끝나지 않은 공고는 부분 집계를 보여 주지 않도록 값을 돌려주지 않는다(응답의 {@code applicantCount=null}).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ApplicantCountFindService {

    private final JobApplicantCountStateRepository stateRepository;
    private final JobApplicationProjectionRepository projectionRepository;

    public Integer findApplicantCount(Long jobPostId) {
        if (!stateRepository.existsByJobPostIdAndSyncedAtIsNotNull(jobPostId)) {
            return null;
        }
        return Math.toIntExact(projectionRepository.countByJobPostIdAndStatus(jobPostId, ApplicationStatus.APPLIED));
    }

    // 초기 적재가 끝난 공고만 담는다. 공고 ID로 묶어 두 번의 쿼리로 센다(공고별 쿼리 없음).
    public Map<Long, Integer> findApplicantCounts(Collection<Long> jobPostIds) {
        if (jobPostIds.isEmpty()) {
            return Map.of();
        }
        List<Long> syncedJobPostIds = stateRepository.findSyncedJobPostIdsByJobPostIdIn(jobPostIds);
        if (syncedJobPostIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> appliedCounts = projectionRepository.countAppliedByJobPostIdIn(syncedJobPostIds).stream()
                .collect(Collectors.toMap(JobApplicantCount::getJobPostId, JobApplicantCount::getApplicantCount));
        Map<Long, Integer> counts = new HashMap<>();
        for (Long jobPostId : syncedJobPostIds) {
            counts.put(jobPostId, Math.toIntExact(appliedCounts.getOrDefault(jobPostId, 0L)));
        }
        return counts;
    }
}
