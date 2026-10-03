package com.workernotfound.job.domain.job.entity;

import com.workernotfound.job.domain.job.entity.enums.MatchingSeatReservationStatus;
import com.workernotfound.job.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

@Getter
@Entity
@Table(
        name = "job_matching_seat_reservations",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_job_matching_seat_reservations_idempotency_key",
                        columnNames = "idempotency_key"
                ),
                @UniqueConstraint(
                        name = "uk_job_matching_seat_reservations_confirm_key",
                        columnNames = "confirm_idempotency_key"
                ),
                @UniqueConstraint(
                        name = "uk_job_matching_seat_reservations_release_key",
                        columnNames = "release_idempotency_key"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobMatchingSeatReservation extends BaseEntity {

    // 시각 컬럼은 DATETIME(6)이고 MySQL은 그보다 정밀한 값을 반올림해 저장한다.
    // 저장 전에 마이크로초 미만을 절삭해 최초 응답의 메모리 값과 이후 DB에서 읽은 값을 같게 한다.
    public static final ChronoUnit TIME_PRECISION = ChronoUnit.MICROS;

    @Column(nullable = false, updatable = false)
    private Long jobPostId;

    @Column(nullable = false, updatable = false)
    private Long matchingId;

    @Column(nullable = false, updatable = false)
    private Long applicationId;

    @Column(nullable = false, updatable = false)
    private Long workerMemberId;

    @Column(nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    // 확정·반환 키는 처음 성공한 명령의 키만 기록하고 이후 덮어쓰지 않는다.
    @Column(length = 100)
    private String confirmIdempotencyKey;

    @Column(length = 100)
    private String releaseIdempotencyKey;

    // 아래 공고 스냅샷은 예약 발급 당시 값이다. 같은 멱등 키의 재요청에 같은 응답을 주기 위해 보관한다.
    @Column(nullable = false, updatable = false)
    private Long jobVersion;

    @Column(nullable = false, updatable = false)
    private Long ownerMemberId;

    @Column(nullable = false, updatable = false)
    private LocalDate workDate;

    @Column(nullable = false, updatable = false)
    private LocalTime startTime;

    @Column(nullable = false, updatable = false)
    private LocalTime endTime;

    @Column(nullable = false, updatable = false)
    private boolean endTimeNextDay;

    @Column(nullable = false, updatable = false)
    private Long lockedAmount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MatchingSeatReservationStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime reservedAt;

    // 발급 시 확정하며 재요청으로 연장하지 않는다.
    @Column(nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime consumedAt;

    private LocalDateTime releasedAt;

    private LocalDateTime expiredAt;

    @Builder
    private JobMatchingSeatReservation(
            Long jobPostId,
            Long matchingId,
            Long applicationId,
            Long workerMemberId,
            String idempotencyKey,
            Long jobVersion,
            Long ownerMemberId,
            LocalDate workDate,
            LocalTime startTime,
            LocalTime endTime,
            boolean endTimeNextDay,
            Long lockedAmount,
            String currency,
            LocalDateTime reservedAt,
            LocalDateTime expiresAt
    ) {
        this.jobPostId = jobPostId;
        this.matchingId = matchingId;
        this.applicationId = applicationId;
        this.workerMemberId = workerMemberId;
        this.idempotencyKey = idempotencyKey;
        this.jobVersion = jobVersion;
        this.ownerMemberId = ownerMemberId;
        this.workDate = workDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.endTimeNextDay = endTimeNextDay;
        this.lockedAmount = lockedAmount;
        this.currency = currency;
        this.status = MatchingSeatReservationStatus.RESERVED;
        this.reservedAt = toStoredTime(reservedAt);
        this.expiresAt = toStoredTime(expiresAt);
    }

    public static LocalDateTime toStoredTime(LocalDateTime time) {
        return time.truncatedTo(TIME_PRECISION);
    }

    public boolean isSameReservationRequest(
            Long jobPostId,
            Long matchingId,
            Long applicationId,
            Long workerMemberId
    ) {
        return this.jobPostId.equals(jobPostId)
                && this.matchingId.equals(matchingId)
                && this.applicationId.equals(applicationId)
                && this.workerMemberId.equals(workerMemberId);
    }

    // 기한 비교에는 절삭하지 않은 현재 시각을 쓴다. expiresAt <= now이면 만료다.
    public boolean isExpiredAt(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public void consume(String confirmIdempotencyKey, LocalDateTime now) {
        requireStatus(MatchingSeatReservationStatus.RESERVED);
        this.status = MatchingSeatReservationStatus.CONSUMED;
        this.confirmIdempotencyKey = confirmIdempotencyKey;
        this.consumedAt = toStoredTime(now);
    }

    public void release(String releaseIdempotencyKey, LocalDateTime now) {
        requireStatus(MatchingSeatReservationStatus.RESERVED);
        this.status = MatchingSeatReservationStatus.RELEASED;
        this.releaseIdempotencyKey = releaseIdempotencyKey;
        this.releasedAt = toStoredTime(now);
    }

    public void expire(LocalDateTime now) {
        requireStatus(MatchingSeatReservationStatus.RESERVED);
        this.status = MatchingSeatReservationStatus.EXPIRED;
        this.expiredAt = toStoredTime(now);
    }

    // 이미 만료로 회수된 예약의 반환 명령을 성공 처리할 때 그 명령 키만 기록한다.
    public void recordReleaseOfExpired(String releaseIdempotencyKey) {
        requireStatus(MatchingSeatReservationStatus.EXPIRED);
        if (this.releaseIdempotencyKey != null) {
            throw new IllegalStateException("반환 명령 키가 이미 기록된 예약입니다.");
        }
        this.releaseIdempotencyKey = releaseIdempotencyKey;
    }

    private void requireStatus(MatchingSeatReservationStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("모집 자리 예약 상태가 " + expected + "가 아닙니다: " + status);
        }
    }
}
