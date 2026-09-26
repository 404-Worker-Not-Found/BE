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

import java.time.LocalDateTime;

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
            LocalDateTime admittedAt,
            LocalDateTime expiresAt
    ) {
        this.jobPostId = jobPostId;
        this.workerMemberId = workerMemberId;
        this.idempotencyKey = idempotencyKey;
        this.jobVersion = jobVersion;
        this.status = ApplicationAdmissionStatus.RESERVED;
        this.admittedAt = admittedAt;
        this.expiresAt = expiresAt;
    }
}
