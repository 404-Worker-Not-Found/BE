package com.workernotfound.job.domain.job.entity;

import com.workernotfound.job.domain.job.entity.enums.PaymentOrderCommandStatus;
import com.workernotfound.job.domain.job.entity.enums.PaymentOrderFailureType;
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
 * payment-service에 결제 주문 생성을 요청하는 영속 명령.
 *
 * <p>공고 생성과 같은 로컬 트랜잭션에서 저장한다. 공고 ID, 결제용 공고 버전, 점주 ID, 금액, 통화, 멱등 키는 발급 당시의 스냅샷이며
 * 생성 후 바꾸지 않는다. 모든 재시도는 이 값만 보내고 현재 공고를 다시 읽지 않으므로, 주문 연결 등으로 공고의 {@code @Version}이
 * 증가해도 같은 요청이 된다. 재결제처럼 새 주문이 필요하면 다음 {@code issueSequence}로 새 명령과 새 키를 발급한다.
 */
@Getter
@Entity
@Table(
        name = "job_payment_order_commands",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_job_payment_order_commands_idempotency_key",
                        columnNames = "idempotency_key"
                ),
                @UniqueConstraint(
                        name = "uk_job_payment_order_commands_job_sequence",
                        columnNames = {"job_post_id", "issue_sequence"}
                ),
                @UniqueConstraint(
                        name = "uk_job_payment_order_commands_order_id",
                        columnNames = "order_id"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobPaymentOrderCommand extends BaseEntity {

    // 시각 컬럼은 DATETIME(6)이다. 조건 비교가 반올림으로 흔들리지 않도록 저장 전 마이크로초로 절삭한다.
    public static final ChronoUnit TIME_PRECISION = ChronoUnit.MICROS;

    @Column(nullable = false, updatable = false)
    private Long jobPostId;

    // 공고별 명령 발급 순번(1부터). 같은 공고의 가장 큰 순번만 공고에 주문을 연결할 수 있다.
    @Column(nullable = false, updatable = false)
    private Integer issueSequence;

    @Column(nullable = false, updatable = false, length = 36)
    private String idempotencyKey;

    // 결제용 공고 버전. 발급 당시 값으로 고정한다.
    @Column(nullable = false, updatable = false)
    private Long jobVersion;

    @Column(nullable = false, updatable = false)
    private Long ownerMemberId;

    // 정수 KRW
    @Column(nullable = false, updatable = false)
    private Long amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentOrderCommandStatus status;

    // 검증을 통과한 주문 ID. 성공 기록과 함께 한 번만 저장한다.
    @Column(length = 64)
    private String orderId;

    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(length = 36)
    private String leaseToken;

    private LocalDateTime leaseExpiresAt;

    private LocalDateTime lastAttemptedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private PaymentOrderFailureType lastFailureType;

    private Integer lastFailureHttpStatus;

    // 상대 응답의 오류 코드만 저장한다. 응답 원문은 저장하지 않는다.
    @Column(length = 50)
    private String lastFailureCode;

    private LocalDateTime completedAt;

    @Builder
    private JobPaymentOrderCommand(
            Long jobPostId,
            Integer issueSequence,
            String idempotencyKey,
            Long jobVersion,
            Long ownerMemberId,
            Long amount,
            String currency,
            LocalDateTime nextAttemptAt
    ) {
        this.jobPostId = jobPostId;
        this.issueSequence = issueSequence;
        this.idempotencyKey = idempotencyKey;
        this.jobVersion = jobVersion;
        this.ownerMemberId = ownerMemberId;
        this.amount = amount;
        this.currency = currency;
        this.status = PaymentOrderCommandStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = toStoredTime(nextAttemptAt);
    }

    public static LocalDateTime toStoredTime(LocalDateTime time) {
        return time.truncatedTo(TIME_PRECISION);
    }

    public boolean isLeasedBy(String token) {
        return status == PaymentOrderCommandStatus.PENDING && token != null && token.equals(leaseToken);
    }

    // 실행권을 가진 처리자가 검증된 주문 ID를 기록한다. 공고 연결과 같은 트랜잭션에서 호출한다.
    public void succeed(String orderId, LocalDateTime now) {
        this.status = PaymentOrderCommandStatus.SUCCEEDED;
        this.orderId = orderId;
        complete(now);
    }

    // 더 늦게 발급된 명령이 있어 결과를 공고에 연결하지 않고 종료한다.
    public void supersede(LocalDateTime now) {
        this.status = PaymentOrderCommandStatus.SUPERSEDED;
        complete(now);
    }

    /**
     * payment-service가 주문 교체를 확정적으로 거절했다(주문 없음). 거절 응답의 분류와 코드를 남기고 종료하며, 이 키는 다시 보내지 않는다.
     */
    public void reject(PaymentOrderFailureType failureType, Integer httpStatus, String failureCode, LocalDateTime now) {
        this.status = PaymentOrderCommandStatus.REJECTED;
        this.lastFailureType = failureType;
        this.lastFailureHttpStatus = httpStatus;
        this.lastFailureCode = failureCode;
        complete(now);
    }

    /**
     * 주문은 만들어졌지만 공고가 더 이상 교체할 수 없는 상태라 연결하지 않고 종료한다. 주문 ID는 남겨, 이 주문의 늦은 예치 알림을
     * 이 공고의 이전 주문으로 판별하게 한다.
     */
    public void supersedeUnlinked(String orderId, LocalDateTime now) {
        this.status = PaymentOrderCommandStatus.SUPERSEDED;
        this.orderId = orderId;
        complete(now);
    }

    public boolean isPending() {
        return status == PaymentOrderCommandStatus.PENDING;
    }

    private void complete(LocalDateTime now) {
        this.completedAt = toStoredTime(now);
        this.leaseToken = null;
        this.leaseExpiresAt = null;
    }
}
