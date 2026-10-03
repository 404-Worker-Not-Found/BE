package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobMatchingSeatReservation;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.MatchingSeatReservationStatus;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.domain.job.repository.JobMatchingSeatReservationRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.global.exception.BusinessException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매칭 확정 Saga의 모집 자리 예약 명령을 처리한다.
 *
 * <p>모든 변경은 공고 행 → 예약 행 순서로 잠근다. 같은 공고의 예약·확정·반환·만료 회수가 공고 행 잠금으로
 * 직렬화되므로 {@code CONSUMED 수 + 유효한 RESERVED 수 <= recruitCount}가 유지된다.
 */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(MatchingSeatReservationProperties.class)
public class MatchingSeatReservationCommandService {

    private static final String CURRENCY_KRW = "KRW";
    private static final Set<JobStatus> MATCHABLE_STATUSES = EnumSet.of(JobStatus.OPEN, JobStatus.MATCHING);
    private static final Set<MatchingSeatReservationStatus> OCCUPYING_STATUSES =
            EnumSet.of(MatchingSeatReservationStatus.RESERVED, MatchingSeatReservationStatus.CONSUMED);

    private final JobPostRepository jobPostRepository;
    private final JobMatchingSeatReservationRepository reservationRepository;
    private final JobWageCalculator jobWageCalculator;
    private final MatchingSeatReservationProperties properties;
    private final Clock clock;

    @Transactional
    public JobMatchingSeatReservation reserve(
            Long jobPostId,
            Long matchingId,
            Long applicationId,
            Long workerMemberId,
            String idempotencyKey
    ) {
        // 공고 행 잠금을 먼저 잡아야 같은 키의 동시 최초 요청이 앞선 커밋 결과를 조회할 수 있다.
        JobPost jobPost = lockJobPost(jobPostId);
        Optional<JobMatchingSeatReservation> existing = reservationRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replayReservation(existing.get(), jobPostId, matchingId, applicationId, workerMemberId);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        validateMatchable(jobPost, now);
        // 스케줄러가 늦어도 만료된 예약이 새 예약을 막지 않도록 같은 잠금 안에서 먼저 회수한다.
        reservationRepository.expireOverdueByJobPostId(jobPostId, now);
        validateNotHeld(jobPostId, matchingId, applicationId);
        validateSeatAvailable(jobPost);

        return reservationRepository.save(JobMatchingSeatReservation.builder()
                .jobPostId(jobPostId)
                .matchingId(matchingId)
                .applicationId(applicationId)
                .workerMemberId(workerMemberId)
                .idempotencyKey(idempotencyKey)
                .jobVersion(jobPost.getVersion())
                .ownerMemberId(jobPost.getOwnerId())
                .workDate(jobPost.getWorkDate())
                .startTime(jobPost.getStartTime())
                .endTime(jobPost.getEndTime())
                .endTimeNextDay(jobPost.isEndTimeNextDay())
                .lockedAmount(jobWageCalculator.calculateWagePerWorker(jobPost))
                .currency(CURRENCY_KRW)
                .reservedAt(now)
                .expiresAt(now.plus(properties.ttl()))
                .build());
    }

    @Transactional
    public int expireOverdue(Long jobPostId) {
        lockJobPost(jobPostId);
        return reservationRepository.expireOverdueByJobPostId(jobPostId, LocalDateTime.now(clock));
    }

    // 같은 키의 동일 요청은 상태와 관계없이 저장된 스냅샷을 그대로 돌려준다. 종료된 예약을 되살리거나 새로 발급하지 않는다.
    private JobMatchingSeatReservation replayReservation(
            JobMatchingSeatReservation reservation,
            Long jobPostId,
            Long matchingId,
            Long applicationId,
            Long workerMemberId
    ) {
        if (!reservation.isSameReservationRequest(jobPostId, matchingId, applicationId, workerMemberId)) {
            throw new BusinessException(JobErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return reservation;
    }

    private void validateMatchable(JobPost jobPost, LocalDateTime now) {
        if (!MATCHABLE_STATUSES.contains(jobPost.getStatus())) {
            throw new BusinessException(JobErrorCode.JOB_NOT_MATCHABLE);
        }
        // 지원 마감이 아니라 근무 시작 시각을 자리 예약 기한으로 사용한다.
        if (!jobPost.getWorkDate().atTime(jobPost.getStartTime()).isAfter(now)) {
            throw new BusinessException(JobErrorCode.WORK_ALREADY_STARTED);
        }
    }

    private void validateNotHeld(Long jobPostId, Long matchingId, Long applicationId) {
        boolean isHeld = reservationRepository.existsByJobPostIdAndStatusInAndMatchingOrApplication(
                jobPostId, OCCUPYING_STATUSES, matchingId, applicationId);
        if (isHeld) {
            throw new BusinessException(JobErrorCode.SEAT_ALREADY_HELD);
        }
    }

    private void validateSeatAvailable(JobPost jobPost) {
        long occupied = reservationRepository.countByJobPostIdAndStatusIn(jobPost.getId(), OCCUPYING_STATUSES);
        if (occupied >= jobPost.getRecruitCount()) {
            throw new BusinessException(JobErrorCode.RECRUITMENT_SEAT_UNAVAILABLE);
        }
    }

    private JobPost lockJobPost(Long jobPostId) {
        return jobPostRepository.findByIdForUpdate(jobPostId)
                .orElseThrow(() -> new BusinessException(JobErrorCode.JOB_NOT_FOUND));
    }
}
