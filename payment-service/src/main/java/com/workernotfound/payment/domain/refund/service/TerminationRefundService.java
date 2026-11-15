package com.workernotfound.payment.domain.refund.service;

import com.workernotfound.payment.domain.payment.entity.PaymentDeposit;
import com.workernotfound.payment.domain.refund.repository.RefundCommandRepository;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 노쇼·확정 근무 취소로 해제한 잠금 금액을 같은 트랜잭션에서 점주 환불로 요청한다. 재모집·공고 재개가 없어 해제된 금액은 다시
 * 쓰이지 않는다. PG 취소는 기존 환불 스케줄러가 실행한다.
 *
 * <p>실제 토스 테스트 환경의 부분 취소·웹훅 확인 전에는 켜지 않는다({@code payment.termination-refund.enabled}, 기본 false).
 * 꺼진 동안 해제된 금액은 나중에 켜도 자동으로 환불하지 않는다. 환불 명령 키는 근무 ID로 고정해 같은 근무의 환불은 하나다.
 * 호출자가 예치 행을 잠근 상태여야 하며, 예치를 만든 주문을 특정할 수 없으면 환불하지 않고 결과만 남긴다.
 */
@Service
public class TerminationRefundService {
  public static final String REQUESTED = "REQUESTED";
  public static final String ORDER_UNRESOLVED = "ORDER_UNRESOLVED";
  private static final String REJECTED_PREFIX = "REJECTED_";

  private final RefundCommandService refunds;
  private final RefundCommandRepository commands;
  private final boolean isEnabled;

  public TerminationRefundService(RefundCommandService refunds, RefundCommandRepository commands,
      @Value("${payment.termination-refund.enabled:false}") boolean isEnabled) {
    this.refunds = refunds;
    this.commands = commands;
    this.isEnabled = isEnabled;
  }

  public record Result(Long refundId, String outcome) {
    static final Result DISABLED = new Result(null, null);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Result requestReleasedAmount(long workId, String eventType, PaymentDeposit deposit, BigDecimal amount) {
    if (!isEnabled) return Result.DISABLED;
    // 주문을 특정할 수 없는 예치에서 환불 명령이 원장 불일치로 롤백되면 잠금 해제까지 되돌아가므로 먼저 확인한다.
    if (commands.findDepositedOrderIds(deposit.getJobPostId()).size() != 1) return new Result(null, ORDER_UNRESOLVED);
    var outcome = refunds.request(key(workId), new RefundCommand(deposit.getJobPostId(), deposit.getOwnerMemberId(),
        amount, deposit.getCurrency(), reason(eventType)));
    if (outcome.refundId() != null) return new Result(outcome.refundId(), REQUESTED);
    return new Result(null, REJECTED_PREFIX + outcome.rejection().name());
  }

  private static String key(long workId) {
    return "work-termination-refund-" + workId;
  }

  private static String reason(String eventType) {
    return "WorkNoShow".equals(eventType) ? "노쇼 근무의 미사용 예치금 환불" : "확정 근무 취소의 미사용 예치금 환불";
  }
}
