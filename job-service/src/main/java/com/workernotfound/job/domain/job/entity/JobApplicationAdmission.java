package com.workernotfound.job.domain.job.entity;

import com.workernotfound.job.domain.job.entity.enums.ApplicationAdmissionStatus;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

@Getter
@Entity
@Table(
        name = "job_application_admissions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_job_application_admissions_idempotency_key",
                columnNames = "idempotency_key"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobApplicationAdmission extends BaseEntity {

    @Column(nullable = false, updatable = false)
    private Long jobPostId;

    @Column(nullable = false, updatable = false)
    private Long workerMemberId;

    @Column(nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private Long jobVersion;

    // 아래 공고 스냅샷은 승인 발급 당시 값이다. 같은 멱등 키의 재요청에 같은 응답을 주기 위해 보관한다.
    @Column(nullable = false, updatable = false)
    private Long ownerMemberId;

    @Column(nullable = false, updatable = false)
    private Long categoryId;

    @Column(nullable = false, updatable = false)
    private LocalDate workDate;

    @Column(nullable = false, updatable = false)
    private LocalTime startTime;

    @Column(nullable = false, updatable = false)
    private LocalTime endTime;

    @Column(nullable = false, updatable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(nullable = false, updatable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApplicationAdmissionStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime admittedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime consumedAt;

    @Builder
    private JobApplicationAdmission(
            Long jobPostId,
            Long workerMemberId,
            String idempotencyKey,
            Long jobVersion,
            Long ownerMemberId,
            Long categoryId,
            LocalDate workDate,
            LocalTime startTime,
            LocalTime endTime,
            BigDecimal latitude,
            BigDecimal longitude,
            LocalDateTime admittedAt,
            LocalDateTime expiresAt
    ) {
        this.jobPostId = jobPostId;
        this.workerMemberId = workerMemberId;
        this.idempotencyKey = idempotencyKey;
        this.jobVersion = jobVersion;
        this.ownerMemberId = ownerMemberId;
        this.categoryId = categoryId;
        this.workDate = workDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.latitude = latitude;
        this.longitude = longitude;
        this.status = ApplicationAdmissionStatus.RESERVED;
        // DATETIME(6)에 저장되기 전부터 최초 응답과 재조회 응답의 정밀도를 맞춘다.
        this.admittedAt = toStoredTime(admittedAt);
        this.expiresAt = toStoredTime(expiresAt);
    }

    private static LocalDateTime toStoredTime(LocalDateTime time) {
        return time == null ? null : time.truncatedTo(ChronoUnit.MICROS);
    }

    public boolean isConsumed() {
        return status == ApplicationAdmissionStatus.CONSUMED;
    }

    // 사용이 확인되지 않은 채 만료 시각이 지난 승인이다. 만료 시각과 같은 시각은 지난 것으로 본다.
    public boolean isOverdueReservation(LocalDateTime now) {
        return status == ApplicationAdmissionStatus.RESERVED && !expiresAt.isAfter(now);
    }

    // EXPIRED는 최종 상태가 아니다. 만료 전에 저장된 지원의 ApplicationSubmitted가 늦게 도착하면 consume으로 CONSUMED가 된다.
    public void expire(LocalDateTime now) {
        if (!isOverdueReservation(now)) {
            throw new IllegalStateException("만료 시각이 지난 RESERVED 지원 접수 승인만 만료할 수 있습니다.");
        }
        this.status = ApplicationAdmissionStatus.EXPIRED;
    }

    // 만료 시각과 같은 시각의 지원은 만료 뒤의 지원으로 본다.
    public boolean isSubmittedBeforeExpiry(LocalDateTime submittedAt) {
        return submittedAt.isBefore(expiresAt);
    }

    // consumedAt은 지원이 저장된 시각(ApplicationSubmitted의 occurredAt)이다. 이벤트를 받아 처리한 시각이 아니다.
    // 지원이 만료 전에 저장됐다면 만료 상태 정리가 먼저 끝났어도 사용 완료로 바꾼다.
    public void consume(LocalDateTime submittedAt) {
        if (isConsumed()) {
            throw new IllegalStateException("이미 사용 완료된 지원 접수 승인입니다.");
        }
        if (!isSubmittedBeforeExpiry(submittedAt)) {
            throw new IllegalStateException("만료 시각 이후에 제출된 지원으로 승인을 사용할 수 없습니다.");
        }
        this.status = ApplicationAdmissionStatus.CONSUMED;
        this.consumedAt = submittedAt;
    }
}
