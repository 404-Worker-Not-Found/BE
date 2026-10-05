package com.workernotfound.job.domain.job.entity;

import com.workernotfound.job.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공고에 연결된 결제 주문별로 마지막에 적용한 예치 상태와 revision.
 *
 * <p>payment-service는 주문마다 revision을 1씩 올려 최신 예치 상태만 다시 보낸다. 전달이 지연·역순이 되어도 이 값보다 낮거나 같은
 * revision은 적용하지 않는다. 공고 행 잠금 아래에서만 생성·갱신한다.
 */
@Getter
@Entity
@Table(
        name = "job_payment_fundings",
        uniqueConstraints = @UniqueConstraint(name = "uk_job_payment_fundings_order_id", columnNames = "order_id")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobPaymentFunding extends BaseEntity {

    @Column(nullable = false, updatable = false)
    private Long jobPostId;

    @Column(nullable = false, updatable = false, length = 64)
    private String orderId;

    @Column(nullable = false)
    private Long fundingRevision;

    @Column(nullable = false)
    private boolean funded;

    @Column(nullable = false)
    private LocalDateTime appliedAt;

    @Builder
    private JobPaymentFunding(
            Long jobPostId,
            String orderId,
            Long fundingRevision,
            boolean funded,
            LocalDateTime appliedAt
    ) {
        this.jobPostId = jobPostId;
        this.orderId = orderId;
        this.fundingRevision = fundingRevision;
        this.funded = funded;
        this.appliedAt = appliedAt.truncatedTo(ChronoUnit.MICROS);
    }

    public boolean isOlderThan(long revision) {
        return fundingRevision < revision;
    }

    public void apply(long revision, boolean funded, LocalDateTime now) {
        if (!isOlderThan(revision)) {
            throw new IllegalStateException("이미 적용한 revision 이하의 예치 상태는 적용할 수 없습니다: " + revision);
        }
        this.fundingRevision = revision;
        this.funded = funded;
        this.appliedAt = now.truncatedTo(ChronoUnit.MICROS);
    }
}
