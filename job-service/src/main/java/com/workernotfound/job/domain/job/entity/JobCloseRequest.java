package com.workernotfound.job.domain.job.entity;

import com.workernotfound.job.domain.job.entity.enums.JobCloseResult;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 점주 수동 마감 요청과 그 처리 결과. 같은 키의 재요청은 이 행의 결과를 그대로 돌려준다. 모든 컬럼은 생성 후 바꾸지 않는다.
 */
@Getter
@Entity
@Table(
        name = "job_close_requests",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_job_close_requests_idempotency_key",
                columnNames = "idempotency_key"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobCloseRequest extends BaseEntity {

    @Column(nullable = false, updatable = false)
    private Long jobPostId;

    @Column(nullable = false, updatable = false)
    private Long ownerMemberId;

    @Column(nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private JobCloseResult result;

    // 요청을 처리할 때의 공고 상태. ALREADY_CLOSED면 CLOSED다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private JobStatus previousStatus;

    @Column(nullable = false, updatable = false)
    private LocalDateTime processedAt;

    @Builder
    private JobCloseRequest(
            Long jobPostId,
            Long ownerMemberId,
            String idempotencyKey,
            JobCloseResult result,
            JobStatus previousStatus,
            LocalDateTime processedAt
    ) {
        this.jobPostId = jobPostId;
        this.ownerMemberId = ownerMemberId;
        this.idempotencyKey = idempotencyKey;
        this.result = result;
        this.previousStatus = previousStatus;
        // DATETIME(6)에 저장된 값과 최초 응답이 같도록 마이크로초로 절삭한다.
        this.processedAt = processedAt.truncatedTo(ChronoUnit.MICROS);
    }

    public boolean isSameRequest(Long jobPostId, Long ownerMemberId) {
        return this.jobPostId.equals(jobPostId) && this.ownerMemberId.equals(ownerMemberId);
    }
}
