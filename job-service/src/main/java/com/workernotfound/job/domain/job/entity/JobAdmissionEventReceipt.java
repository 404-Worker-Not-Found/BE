package com.workernotfound.job.domain.job.entity;

import com.workernotfound.job.domain.job.event.ApplicationSubmittedEvent;
import com.workernotfound.job.domain.job.service.AdmissionConsumptionResult;
import com.workernotfound.job.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@code ApplicationSubmitted} 이벤트의 영구 수신 기록과 그때의 처리 결과.
 *
 * <p>승인 사용 완료 처리와 같은 트랜잭션에서 저장한다. 같은 eventId가 다시 오면 이 기록으로 중복을 판단하고 저장된 결과를 돌려준다.
 * 이벤트 내용도 보존해 같은 eventId에 다른 내용이 오는지 비교한다. 모든 컬럼은 생성 후 바꾸지 않는다.
 */
@Getter
@Entity
@Table(
        name = "job_admission_event_receipts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_job_admission_event_receipts_event_id",
                columnNames = "event_id"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobAdmissionEventReceipt extends BaseEntity {

    @Column(nullable = false, updatable = false, length = 128)
    private String eventId;

    @Column(nullable = false, updatable = false)
    private Long applicationId;

    @Column(nullable = false, updatable = false)
    private Long revision;

    @Column(updatable = false)
    private Long admissionId;

    @Column(updatable = false)
    private Long jobPostId;

    @Column(updatable = false)
    private Long workerMemberId;

    @Column(updatable = false)
    private LocalDateTime occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 40)
    private AdmissionConsumptionResult result;

    // job-service가 이벤트를 처리한 시각이다. 승인 판단에는 쓰지 않고 운영 확인용으로만 남긴다.
    @Column(nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    @Builder
    private JobAdmissionEventReceipt(
            ApplicationSubmittedEvent event,
            AdmissionConsumptionResult result,
            LocalDateTime receivedAt
    ) {
        this.eventId = event.eventId();
        this.applicationId = event.applicationId();
        this.revision = event.revision();
        this.admissionId = event.admissionId();
        this.jobPostId = event.jobPostId();
        this.workerMemberId = event.workerMemberId();
        this.occurredAt = toStoredTime(event.occurredAt());
        this.result = result;
        this.receivedAt = toStoredTime(receivedAt);
    }

    public boolean isSameEvent(ApplicationSubmittedEvent event) {
        return eventId.equals(event.eventId())
                && applicationId.equals(event.applicationId())
                && revision.equals(event.revision())
                && Objects.equals(admissionId, event.admissionId())
                && Objects.equals(jobPostId, event.jobPostId())
                && Objects.equals(workerMemberId, event.workerMemberId())
                && Objects.equals(occurredAt, toStoredTime(event.occurredAt()));
    }

    // 저장 정밀도(DATETIME(6))로 절삭해 저장값과 재전송 이벤트를 같은 기준으로 비교한다.
    private static LocalDateTime toStoredTime(LocalDateTime value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }
}
