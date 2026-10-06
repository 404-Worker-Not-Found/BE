package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.dto.request.JobSearchRequest;
import com.workernotfound.job.domain.job.dto.request.OwnerJobSearchRequest;
import com.workernotfound.job.domain.job.dto.response.JobCardResponse;
import com.workernotfound.job.domain.job.dto.response.JobDetailResponse;
import com.workernotfound.job.domain.job.dto.response.JobPaymentChangeResponse;
import com.workernotfound.job.domain.job.dto.response.JobPaymentOrderResponse;
import com.workernotfound.job.domain.job.dto.response.JobSearchResponse;
import com.workernotfound.job.domain.job.dto.response.OwnerJobCardResponse;
import com.workernotfound.job.domain.job.dto.response.OwnerJobListResponse;
import com.workernotfound.job.domain.job.entity.IndustryCategory;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.UrgencyLevel;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.domain.job.repository.IndustryCategoryRepository;
import com.workernotfound.job.domain.job.repository.JobConsumedSeatCount;
import com.workernotfound.job.domain.job.repository.JobMatchingSeatReservationRepository;
import com.workernotfound.job.domain.job.repository.JobPaymentChangeRequestRepository;
import com.workernotfound.job.domain.job.repository.JobPaymentOrderCommandRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JobFindService {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final Sort OWNER_JOB_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final JobPostRepository jobPostRepository;
    private final IndustryCategoryRepository industryCategoryRepository;
    private final JobPaymentOrderCommandRepository paymentOrderCommandRepository;
    private final JobPaymentChangeRequestRepository paymentChangeRequestRepository;
    private final JobMatchingSeatReservationRepository seatReservationRepository;

    public JobPost findJobPost(Long jobId) {
        return jobPostRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException(JobErrorCode.JOB_NOT_FOUND));
    }

    // 사용자 상세 조회. 공개 전 공고를 다른 회원에게는 존재하지 않는 공고와 같은 404로 응답한다.
    // findJobPost는 상태와 관계없이 조회하므로 이 접근 제한을 내부 처리에 적용하지 않는다.
    public JobDetailResponse findJobDetail(Long jobId, Long viewerMemberId) {
        JobPost post = findJobPost(jobId);
        if (!post.isVisibleTo(viewerMemberId)) {
            throw new BusinessException(JobErrorCode.JOB_NOT_FOUND);
        }
        Map<Long, String> categoryNameMap = buildCategoryNameMap();
        String categoryName = resolveCategoryName(categoryNameMap, post.getCategoryId());
        return JobDetailResponse.of(post, categoryName);
    }

    // 점주 본인 공고의 결제 주문 생성 상태. 다른 회원의 공고는 존재 여부를 드러내지 않도록 404로 응답한다.
    public JobPaymentOrderResponse findJobPaymentOrder(Long jobId, Long ownerMemberId) {
        JobPost post = findJobPost(jobId);
        if (!post.getOwnerId().equals(ownerMemberId)) {
            throw new BusinessException(JobErrorCode.JOB_NOT_FOUND);
        }
        if (post.getPaymentOrderId() != null) {
            return JobPaymentOrderResponse.linked(post, findLatestPaymentChange(jobId));
        }
        return paymentOrderCommandRepository.findFirstByJobPostIdOrderByIssueSequenceDesc(jobId)
                .map(command -> JobPaymentOrderResponse.pending(post, command))
                .orElseGet(() -> JobPaymentOrderResponse.notRequested(post));
    }

    // 결제 조건 변경·재결제는 주문이 연결된 공고에만 발급되므로 연결된 경우에만 함께 보여 준다.
    private JobPaymentChangeResponse findLatestPaymentChange(Long jobId) {
        return paymentChangeRequestRepository.findFirstByJobPostIdOrderByIdDesc(jobId)
                .map(request -> JobPaymentChangeResponse.of(
                        request, paymentOrderCommandRepository.findById(request.getCommandId()).orElseThrow()))
                .orElse(null);
    }

    // 점주 본인 공고 목록. 결제 대기·예치 차단·마감 공고를 모두 포함하고 DB 조건과 페이지 쿼리로 조회한다.
    public OwnerJobListResponse findOwnerJobs(Long ownerMemberId, OwnerJobSearchRequest request) {
        int page = request.page() != null ? request.page() : DEFAULT_PAGE;
        int size = request.size() != null ? request.size() : DEFAULT_PAGE_SIZE;
        Page<JobPost> posts = fetchOwnerJobs(ownerMemberId, request.status(), PageRequest.of(page, size, OWNER_JOB_SORT));
        Map<Long, Long> consumedCounts = countConsumedSeats(posts.getContent());

        List<OwnerJobCardResponse> cards = posts.getContent().stream()
                .map(post -> OwnerJobCardResponse.of(post, consumedCounts.getOrDefault(post.getId(), 0L)))
                .toList();
        return new OwnerJobListResponse(page, size, posts.getTotalElements(), posts.getTotalPages(), cards);
    }

    private Page<JobPost> fetchOwnerJobs(Long ownerMemberId, JobStatus status, PageRequest pageRequest) {
        if (pageRequest.getOffset() > Integer.MAX_VALUE) {
            return findOwnerJobsWithLargeOffset(ownerMemberId, status, pageRequest);
        }
        if (status == null) {
            return jobPostRepository.findByOwnerId(ownerMemberId, pageRequest);
        }
        return jobPostRepository.findByOwnerIdAndStatus(ownerMemberId, status, pageRequest);
    }

    private Page<JobPost> findOwnerJobsWithLargeOffset(Long ownerMemberId, JobStatus status, PageRequest pageRequest) {
        List<JobPost> posts = jobPostRepository.findByOwnerIdAndOptionalStatusWithOffset(
                ownerMemberId, status == null ? null : status.name(), pageRequest.getPageSize(), pageRequest.getOffset());
        long totalCount = status == null
                ? jobPostRepository.countByOwnerId(ownerMemberId)
                : jobPostRepository.countByOwnerIdAndStatus(ownerMemberId, status);
        return new PageImpl<>(posts, pageRequest, totalCount);
    }

    // 페이지에 담긴 공고 ID로 묶어 한 번에 집계한다. 공고마다 따로 세면 N+1 쿼리가 된다.
    private Map<Long, Long> countConsumedSeats(List<JobPost> posts) {
        if (posts.isEmpty()) {
            return Map.of();
        }
        List<Long> jobPostIds = posts.stream().map(JobPost::getId).toList();
        return seatReservationRepository.countConsumedByJobPostIdIn(jobPostIds).stream()
                .collect(Collectors.toMap(JobConsumedSeatCount::getJobPostId, JobConsumedSeatCount::getConsumedCount));
    }

    public JobSearchResponse findJobs(JobSearchRequest request) {
        validateSearchRequest(request);
        List<JobPost> candidates = fetchCandidates(request);
        Map<Long, String> categoryNameMap = buildCategoryNameMap();

        List<JobCardResponse> filtered = candidates.stream()
                .filter(job -> matchesWage(job, request))
                .filter(job -> matchesStartTime(job, request))
                .filter(job -> matchesUrgency(job, request))
                .filter(job -> job.getApplicationDeadline().isAfter(LocalDateTime.now()))
                // 예치 차단 공고는 신규 지원을 받지 않으므로 검색에서 뺀다. 상세 조회는 기존 지원자를 위해 유지한다.
                .filter(job -> !job.isFundingBlocked())
                .map(job -> {
                    Double distanceKm = calculateDistance(job, request);
                    return JobCardResponse.of(job, resolveCategoryName(categoryNameMap, job.getCategoryId()), distanceKm);
                })
                .filter(card -> matchesDistance(card, request))
                .sorted(buildComparator(request.type()))
                .collect(Collectors.toList());

        int page = request.page() != null ? request.page() : 0;
        int size = request.size() != null ? request.size() : 20;
        int total = filtered.size();
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);

        return JobSearchResponse.of(filtered.subList(fromIndex, toIndex), page, size, total);
    }

    private void validateSearchRequest(JobSearchRequest request) {
        if (request.minWage() != null && request.maxWage() != null
                && request.minWage() > request.maxWage()) {
            throw new BusinessException(JobErrorCode.INVALID_SEARCH_CONDITION, "minWage는 maxWage보다 클 수 없습니다.");
        }
        if (request.startTimeFrom() != null && request.startTimeTo() != null
                && request.startTimeFrom().isAfter(request.startTimeTo())) {
            throw new BusinessException(JobErrorCode.INVALID_SEARCH_CONDITION, "startTimeFrom은 startTimeTo보다 이후일 수 없습니다.");
        }
        if (request.maxDistanceKm() != null && request.maxDistanceKm() < 0) {
            throw new BusinessException(JobErrorCode.INVALID_SEARCH_CONDITION, "maxDistanceKm은 0 이상이어야 합니다.");
        }
        if (request.maxDistanceKm() != null
                && (request.workerLat() == null || request.workerLng() == null)) {
            throw new BusinessException(JobErrorCode.INVALID_SEARCH_CONDITION, "maxDistanceKm을 사용하려면 workerLat와 workerLng가 필요합니다.");
        }
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

    private String resolveCategoryName(Map<Long, String> categoryNameMap, Long categoryId) {
        String name = categoryNameMap.get(categoryId);
        if (name == null) {
            throw new BusinessException(JobErrorCode.CATEGORY_NOT_FOUND);
        }
        return name;
    }

    private boolean matchesWage(JobPost job, JobSearchRequest request) {
        if (request.minWage() != null && job.getBaseHourlyWage() < request.minWage()) {
            return false;
        }
        if (request.maxWage() != null && job.getBaseHourlyWage() > request.maxWage()) {
            return false;
        }
        return true;
    }

    private boolean matchesStartTime(JobPost job, JobSearchRequest request) {
        if (request.startTimeFrom() != null && job.getStartTime().isBefore(request.startTimeFrom())) {
            return false;
        }
        if (request.startTimeTo() != null && job.getStartTime().isAfter(request.startTimeTo())) {
            return false;
        }
        return true;
    }

    private boolean matchesUrgency(JobPost job, JobSearchRequest request) {
        if ("URGENT".equalsIgnoreCase(request.type())) {
            return job.getUrgencyLevel() == UrgencyLevel.HIGH;
        }
        return true;
    }

    private boolean matchesDistance(JobCardResponse card, JobSearchRequest request) {
        if (request.workerLat() == null || request.workerLng() == null) {
            return true;
        }
        if (request.maxDistanceKm() == null) {
            return true;
        }
        return card.distanceKm() != null && card.distanceKm() <= request.maxDistanceKm();
    }

    private Double calculateDistance(JobPost job, JobSearchRequest request) {
        if (request.workerLat() == null || request.workerLng() == null) {
            return null;
        }
        return haversineKm(
                request.workerLat().doubleValue(),
                request.workerLng().doubleValue(),
                job.getLatitude().doubleValue(),
                job.getLongitude().doubleValue()
        );
    }

    private Comparator<JobCardResponse> buildComparator(String type) {
        if ("URGENT".equalsIgnoreCase(type)) {
            return Comparator.comparingLong(JobCardResponse::remainingMinutes);
        }
        return Comparator.comparing(JobCardResponse::distanceKm, Comparator.nullsLast(Double::compareTo));
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
