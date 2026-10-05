package com.workernotfound.job.domain.job.entity;

import com.workernotfound.job.domain.job.entity.enums.RefundReviewReason;
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

/**
 * 공고 상태에 반영되지 않은 예치 확인의 환불 검토 대상.
 *
 * <p>주문당 한 건이며 처음 검토 대상이 된 예치 상태 수신 기록에 연결한다. 같은 주문의 이후 알림은 새 행을 만들지 않는다. 최신 공고 상태를
 * 바꾸지 않고 이전 주문의 금전적 사실만 보존하며, 실제 환불·정산은 하지 않는다. 생성 후 바꾸지 않는다.
 */
@Getter
@Entity
@Table(
        name = "job_payment_refund_reviews",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_job_payment_refund_reviews_order_id", columnNames = "order_id"),
                @UniqueConstraint(name = "uk_job_payment_refund_reviews_receipt", columnNames = "receipt_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobPaymentRefundReview extends BaseEntity {

    @Column(nullable = false, updatable = false)
    private Long jobPostId;

    @Column(nullable = false, updatable = false, length = 64)
    private String orderId;

    @Column(nullable = false, updatable = false)
    private Long receiptId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 40)
    private RefundReviewReason reason;

    @Builder
    private JobPaymentRefundReview(Long jobPostId, String orderId, Long receiptId, RefundReviewReason reason) {
        this.jobPostId = jobPostId;
        this.orderId = orderId;
        this.receiptId = receiptId;
        this.reason = reason;
    }
}
