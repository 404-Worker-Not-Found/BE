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
        this.admittedAt = admittedAt;
        this.expiresAt = expiresAt;
    }
}
