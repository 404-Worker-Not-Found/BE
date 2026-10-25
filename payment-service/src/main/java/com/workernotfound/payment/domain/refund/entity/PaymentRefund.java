package com.workernotfound.payment.domain.refund.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.*;

/**
 * 점주에게 돌려줄 미사용 예치금 환불. 예치 하나와 그 예치를 만든 결제 주문 하나에 속한다.
 *
 * <p>금액은 생성할 때 예치의 가용 잔액에서 환불 예약으로 옮겨 둔다. 금액 이동은 {@code PaymentDeposit}이 맡고 이 엔티티는 상태만
 * 바꾼다. 실행 임대·재시도 시각은 {@code RefundExecutionRepository}가 JDBC로 관리하므로 여기에 매핑하지 않는다.
 *
 * <p>reviewRequired는 PG 결과가 장부와 맞지 않아 자동 실행을 멈춘 상태다. 상태는 REQUESTED로 두고 금액도 환불 예약에 남긴다.
 */
@Entity
@Table(name = "payment_refunds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentRefund {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(nullable = false) private Long depositId;
  @Column(nullable = false, length = 64) private String orderId;
  @Column(nullable = false) private Long jobPostId;
  @Column(nullable = false) private Long ownerMemberId;
  @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
  @Column(nullable = false, length = 3) private String currency;
  @Column(nullable = false, length = 200) private String reason;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private RefundStatus status;
  @Column(nullable = false) private LocalDateTime requestedAt;
  private LocalDateTime completedAt;
  @Column(nullable = false) private boolean reviewRequired;
  @Column(length = 64) private String failureCode;
  @Column(length = 64) private String providerTransactionKey;
  @Version private Long version;

  @Builder
  private PaymentRefund(Long depositId, String orderId, Long jobPostId, Long ownerMemberId,
      BigDecimal amount, String currency, String reason) {
    this.depositId = depositId;
    this.orderId = orderId;
    this.jobPostId = jobPostId;
    this.ownerMemberId = ownerMemberId;
    this.amount = amount;
    this.currency = currency;
    this.reason = reason;
    this.status = RefundStatus.REQUESTED;
    this.requestedAt = now();
  }

  // 토스 취소 거래 키는 응답에서 금액이 같은 취소를 찾았을 때만 남긴다. 성공 판단은 취소 금액 합계로 한다.
  public void succeed(String transactionKey) {
    transition(RefundStatus.SUCCEEDED);
    providerTransactionKey = transactionKey;
    completedAt = now();
  }

  public void fail(String code) {
    if (code == null || code.isBlank()) throw new IllegalArgumentException("환불 실패 코드가 필요합니다.");
    transition(RefundStatus.FAILED);
    failureCode = code;
    completedAt = now();
  }

  public void requireReview(String reason) {
    if (status != RefundStatus.REQUESTED) throw new IllegalStateException("결과가 확정된 환불은 검토로 돌릴 수 없습니다.");
    reviewRequired = true;
    failureCode = reason;
  }

  private void transition(RefundStatus next) {
    if (!status.canTransitionTo(next))
      throw new IllegalStateException("허용되지 않는 환불 상태 전이입니다: " + status + " -> " + next);
    status = next;
  }

  // DATETIME(6)에 저장되는 값과 같게 맞춰 재조회 응답이 처음 응답과 달라지지 않게 한다.
  private static LocalDateTime now() {
    return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
  }
}
