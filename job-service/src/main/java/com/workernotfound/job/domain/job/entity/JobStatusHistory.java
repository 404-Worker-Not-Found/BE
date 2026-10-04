package com.workernotfound.job.domain.job.entity;

import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "job_status_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long jobPostId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private JobStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private JobStatus toStatus;

    // 처리 주체와 사유를 "주체:사유" 형식으로 기록한다. 예: SYSTEM:RECRUITMENT_FILLED
    @Column(updatable = false)
    private String reason;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private JobStatusHistory(
            Long jobPostId,
            JobStatus fromStatus,
            JobStatus toStatus,
            String reason,
            LocalDateTime createdAt
    ) {
        this.jobPostId = jobPostId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.createdAt = createdAt;
    }
}
