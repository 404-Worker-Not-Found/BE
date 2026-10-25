package com.workernotfound.payment.domain.refund.service;

import com.workernotfound.payment.domain.refund.repository.RefundExecutionRepository.Claim;
import com.workernotfound.payment.external.toss.*;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * REQUESTED 환불을 토스 결제 취소로 실행한다. DB 트랜잭션 밖에서 PG를 호출하고, 결과는 PG 취소 합계로만 확정한다.
 *
 * <p>첫 시도는 바로 취소를 보낸다. 다시 점유한 환불은 결제를 먼저 조회해, 이미 반영됐으면 성공으로 확정하고 반영되지 않았을 때만 같은
 * 멱등 키로 다시 보낸다. 토스가 확정적으로 거절해도 조회로 반영되지 않았음을 확인한 뒤에만 실패로 확정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundExecutionService {
  // 같은 요청을 다시 보내도 결과가 바뀌지 않는 결제 취소 거절. 일시 오류·인증 오류·알 수 없는 코드는 결과 미확정으로 재시도한다.
  private static final Set<String> DEFINITIVE_REJECTIONS = Set.of(
      "ALREADY_CANCELED_PAYMENT", "ALREADY_REFUND_PAYMENT", "EXCEED_CANCEL_AMOUNT_DISCOUNT_AMOUNT",
      "EXCEED_CANCEL_LIMIT", "EXCEED_MAX_REFUND_DUE", "INVALID_REQUEST", "NOT_ALLOWED_PARTIAL_REFUND",
      "NOT_CANCELABLE_AMOUNT", "NOT_CANCELABLE_PAYMENT", "NOT_CANCELABLE_PAYMENT_FOR_DORMANT_USER",
      "NOT_FOUND_PAYMENT", "NOT_MATCHES_REFUNDABLE_AMOUNT", "REFUND_REJECTED");

  private final RefundExecutionTransactionService transactions;
  private final TossGateway toss;

  public int executeDue(int limit) {
    int executed = 0;
    for (; executed < limit; executed++) {
      Claim claim = transactions.claim(null);
      if (claim == null) break;
      run(claim);
    }
    return executed;
  }

  public void execute(Long refundId) {
    Claim claim = transactions.claim(refundId);
    if (claim != null) run(claim);
  }

  private void run(Claim claim) {
    try {
      if (claim.attempt() > 1 && isResolvedBeforeResend(claim)) return;
      send(claim);
    } catch (RuntimeException exception) {
      log.warn("환불 결과 미확정, 재시도 예정: refundId={}, type={}", claim.refundId(),
          exception.getClass().getSimpleName());
      retry(claim, exception);
    }
  }

  // 응답 유실 뒤에는 같은 키로 다시 보내기 전에 PG 상태를 확인한다. 멱등 키 유효 기간이 지나면 다시 보내지 않는다.
  private boolean isResolvedBeforeResend(Claim claim) {
    if (claim.isPastIdempotencyWindow()) {
      transactions.review(claim, "IDEMPOTENCY_WINDOW_EXPIRED");
      return true;
    }
    return transactions.settle(claim, toss.getPayment(claim.paymentKey()), null);
  }

  private void send(Claim claim) {
    TossPayment result;
    try {
      result = toss.cancel(claim.paymentKey(), claim.amount(), claim.reason(), claim.idempotencyKey());
    } catch (TossClientException exception) {
      if (!isDefinitiveRejection(exception)) throw exception;
      transactions.settle(claim, toss.getPayment(claim.paymentKey()), exception.remoteCode());
      return;
    }
    if (!transactions.settle(claim, result, null)) transactions.retry(claim);
  }

  private boolean isDefinitiveRejection(TossClientException exception) {
    return exception.remoteCode() != null && DEFINITIVE_REJECTIONS.contains(exception.remoteCode());
  }

  private void retry(Claim claim, RuntimeException cause) {
    try {
      transactions.retry(claim);
    } catch (RuntimeException retryFailure) {
      // 임대가 만료되면 다음 실행이 다시 점유하므로 결과를 잃지 않는다.
      retryFailure.addSuppressed(cause);
      log.warn("환불 재시도 예약 실패: refundId={}, type={}", claim.refundId(),
          retryFailure.getClass().getSimpleName());
    }
  }
}
