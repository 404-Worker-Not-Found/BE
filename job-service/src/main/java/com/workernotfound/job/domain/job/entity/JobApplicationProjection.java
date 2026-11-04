package com.workernotfound.job.domain.job.entity;

import com.workernotfound.job.domain.job.entity.enums.ApplicationStatus;
import com.workernotfound.job.domain.job.exception.InvalidApplicationStateException;
import com.workernotfound.job.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 지원자 수 projection의 지원별 현재 상태. 원본은 matching-service {@code applications}이며 ID도 그 지원 ID다.
 *
 * <p>이벤트와 스냅샷을 같은 revision 비교로 반영한다. 더 큰 revision만 상태를 바꾸고, 같은 revision은 바꾸지 않으며, 작은
 * revision은 무시한다. 같은 revision에 다른 상태가 오면 계약 위반이다. 호출자는 공고별 적재 상태 행을 잠근 트랜잭션에서만 바꾼다.
 */
@Getter
@Entity
@Table(name = "job_application_projections")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobApplicationProjection extends BaseTimeEntity {

    @Id
    @Column(name = "application_id")
    private Long applicationId;

    @Column(nullable = false, updatable = false)
    private Long jobPostId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApplicationStatus status;

    @Column(nullable = false)
    private Long revision;

    // 마지막으로 상태를 바꾼 이벤트 ID. 스냅샷으로 바뀌었으면 null이다. 운영 확인용이며 순서 판단에 쓰지 않는다.
    @Column(length = 128)
    private String eventId;

    @Builder
    private JobApplicationProjection(
            Long applicationId,
            Long jobPostId,
            ApplicationStatus status,
            Long revision,
            String eventId
    ) {
        this.applicationId = applicationId;
        this.jobPostId = jobPostId;
        this.status = status;
        this.revision = revision;
        this.eventId = eventId;
    }

    // 반영해서 상태나 revision이 바뀌었으면 true다.
    public boolean change(Long jobPostId, ApplicationStatus status, Long revision, String eventId) {
        if (!this.jobPostId.equals(jobPostId)) {
            throw new InvalidApplicationStateException("저장된 지원과 다른 공고 ID의 지원 상태가 전달되었습니다.");
        }
        if (revision < this.revision) {
            return false;
        }
        if (revision.equals(this.revision)) {
            requireSameStatus(status);
            return false;
        }
        this.status = status;
        this.revision = revision;
        this.eventId = eventId;
        return true;
    }

    private void requireSameStatus(ApplicationStatus status) {
        if (this.status != status) {
            throw new InvalidApplicationStateException("같은 revision에 다른 지원 상태가 전달되었습니다.");
        }
    }
}
