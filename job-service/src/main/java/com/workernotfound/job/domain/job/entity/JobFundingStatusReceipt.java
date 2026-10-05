package com.workernotfound.job.domain.job.entity;

import com.workernotfound.job.domain.job.entity.enums.FundingSkipReason;
import com.workernotfound.job.domain.job.entity.enums.FundingStatusResult;
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
 * 검증을 통과한 예치 상태 알림의 영구 수신 기록과 그때 돌려준 응답.
 *
 * <p>요청 필드 전체를 보존해 같은 키·같은 주문 revision의 재전송이 같은 내용인지 비교한다. 응답 필드(처리 결과, 처리 직후 공고 상태와
 * 예치 차단 여부)도 함께 저장해, 이후 공고가 바뀌어도 같은 명령에는 처음 응답을 그대로 돌려준다. 모든 컬럼은 생성 후 바꾸지 않는다.
 */
@Getter
@Entity
@Table(
        name = "job_funding_status_receipts",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_job_funding_status_receipts_idempotency_key",
                        columnNames = "idempotency_key"
                ),
                @UniqueConstraint(
                        name = "uk_job_funding_status_receipts_order_revision",
                        columnNames = {"order_id", "funding_revision"}
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobFundingStatusReceipt extends BaseEntity {

    @Column(nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private Long jobPostId;

    @Column(nullable = false, updatable = false, length = 64)
    private String orderId;

    // 알림의 결제용 공고 버전. 현재 @Version이 아니다.
    @Column(nullable = false, updatable = false)
    private Long jobVersion;

    @Column(nullable = false, updatable = false)
    private Long ownerMemberId;

    // 정수 KRW
    @Column(nullable = false, updatable = false)
    private Long amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(nullable = false, updatable = false)
    private Long fundingRevision;

    @Column(nullable = false, updatable = false)
    private boolean funded;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private FundingStatusResult result;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false, length = 40)
    private FundingSkipReason skipReason;

    // 처리 직후의 공고 상태와 예치 차단 여부. 재요청 응답에 그대로 쓴다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private JobStatus jobStatus;

    @Column(nullable = false, updatable = false)
    private boolean fundingBlocked;

    // 공고 공개에 쓰이지 못한 예치 확인이다. 실제 환불은 하지 않고 후속 환불·정산 기능이 검토할 대상으로만 남긴다.
    @Column(nullable = false, updatable = false)
    private boolean refundReviewRequired;

    @Column(nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    @Builder
    private JobFundingStatusReceipt(
            String idempotencyKey,
            Long jobPostId,
            FundingStatusNotification notification,
            FundingStatusResult result,
            FundingSkipReason skipReason,
            JobStatus jobStatus,
            boolean fundingBlocked,
            boolean refundReviewRequired,
            LocalDateTime receivedAt
    ) {
        this.idempotencyKey = idempotencyKey;
        this.jobPostId = jobPostId;
        this.orderId = notification.orderId();
        this.jobVersion = notification.jobVersion();
        this.ownerMemberId = notification.ownerMemberId();
        this.amount = notification.amount();
        this.currency = notification.currency();
        this.fundingRevision = notification.fundingRevision();
        this.funded = notification.funded();
        this.result = result;
        this.skipReason = skipReason;
        this.jobStatus = jobStatus;
        this.fundingBlocked = fundingBlocked;
        this.refundReviewRequired = refundReviewRequired;
        this.receivedAt = receivedAt.truncatedTo(ChronoUnit.MICROS);
    }

    public boolean isSameNotification(Long jobPostId, FundingStatusNotification notification) {
        return this.jobPostId.equals(jobPostId) && toNotification().equals(notification);
    }

    private FundingStatusNotification toNotification() {
        return new FundingStatusNotification(
                orderId, jobVersion, ownerMemberId, amount, currency, fundingRevision, funded);
    }
}
