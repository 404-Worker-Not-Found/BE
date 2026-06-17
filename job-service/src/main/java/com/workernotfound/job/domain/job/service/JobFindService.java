package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.dto.request.JobSearchRequest;
import com.workernotfound.job.domain.job.dto.response.JobCardResponse;
import com.workernotfound.job.domain.job.dto.response.JobDetailResponse;
import com.workernotfound.job.domain.job.dto.response.JobSearchResponse;
import com.workernotfound.job.domain.job.entity.IndustryCategory;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.JobStatus;
import com.workernotfound.job.domain.job.entity.UrgencyLevel;
import com.workernotfound.job.domain.job.repository.IndustryCategoryRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JobFindService {

    private final JobPostRepository jobPostRepository;
    private final IndustryCategoryRepository industryCategoryRepository;

    public JobDetailResponse getJobDetail(Long jobId) {
        JobPost post = jobPostRepository.findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("공고를 찾을 수 없습니다. id=" + jobId));
        Map<Long, String> categoryNameMap = buildCategoryNameMap();
        String categoryName = categoryNameMap.getOrDefault(post.getCategoryId(), "");
        return JobDetailResponse.of(post, categoryName, 0);
    }

    public JobSearchResponse searchJobs(JobSearchRequest request) {
        List<JobPost> candidates = fetchCandidates(request);
        Map<Long, String> categoryNameMap = buildCategoryNameMap();

        List<JobCardResponse> result = candidates.stream()
                .filter(job -> filterByWage(job, request))
                .filter(job -> filterByStartTime(job, request))
                .filter(job -> filterByUrgency(job, request))
                .filter(job -> job.getApplicationDeadline().isAfter(LocalDateTime.now()))
                .map(job -> {
                    double distanceKm = calculateDistance(job, request);
                    return JobCardResponse.of(job, categoryNameMap.getOrDefault(job.getCategoryId(), ""), distanceKm);
                })
                .filter(card -> filterByDistance(card, request))
                .sorted(buildComparator(request))
                .collect(Collectors.toList());

        return JobSearchResponse.of(result);
    }

    private List<JobPost> fetchCandidates(JobSearchRequest request) {
        if (request.categoryIds() != null && !request.categoryIds().isEmpty()) {
            return jobPostRepository.findByStatusAndCategoryIdIn(JobStatus.OPEN, request.categoryIds());
        }
        return jobPostRepository.findByStatus(JobStatus.OPEN);
    }

    private Map<Long, String> buildCategoryNameMap() {
        return industryCategoryRepository.findAll().stream()
                .collect(Collectors.toMap(IndustryCategory::getId, IndustryCategory::getName));
    }

    private boolean filterByWage(JobPost job, JobSearchRequest request) {
        if (request.minWage() != null && job.getBaseHourlyWage() < request.minWage()) {
            return false;
        }
        if (request.maxWage() != null && job.getBaseHourlyWage() > request.maxWage()) {
            return false;
        }
        return true;
    }

    private boolean filterByStartTime(JobPost job, JobSearchRequest request) {
        if (request.startTimeFrom() != null && job.getStartTime().isBefore(request.startTimeFrom())) {
            return false;
        }
        if (request.startTimeTo() != null && job.getStartTime().isAfter(request.startTimeTo())) {
            return false;
        }
        return true;
    }

    private boolean filterByUrgency(JobPost job, JobSearchRequest request) {
        if ("URGENT".equalsIgnoreCase(request.type())) {
            return job.getUrgencyLevel() == UrgencyLevel.HIGH;
        }
        return true;
    }

    private boolean filterByDistance(JobCardResponse card, JobSearchRequest request) {
        if (request.workerLat() == null || request.workerLng() == null) {
            return true;
        }
        if (request.maxDistanceKm() == null) {
            return true;
        }
        return card.distanceKm() <= request.maxDistanceKm();
    }

    private double calculateDistance(JobPost job, JobSearchRequest request) {
        if (request.workerLat() == null || request.workerLng() == null) {
            return 0.0;
        }
        return haversineKm(
                request.workerLat().doubleValue(),
                request.workerLng().doubleValue(),
                job.getLatitude().doubleValue(),
                job.getLongitude().doubleValue()
        );
    }

    private Comparator<JobCardResponse> buildComparator(JobSearchRequest request) {
        if ("URGENT".equalsIgnoreCase(request.type())) {
            return Comparator.comparingLong(JobCardResponse::remainingMinutes);
        }
        return Comparator.comparingDouble(JobCardResponse::distanceKm);
    }

    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
