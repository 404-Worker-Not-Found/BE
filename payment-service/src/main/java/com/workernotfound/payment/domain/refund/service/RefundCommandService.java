package com.workernotfound.payment.domain.refund.service;

import com.workernotfound.payment.domain.payment.entity.PaymentDeposit;
import com.workernotfound.payment.domain.payment.exception.PaymentErrorCode;
import com.workernotfound.payment.domain.payment.repository.PaymentDepositRepository;
import com.workernotfound.payment.domain.refund.dto.request.RefundRequest;
import com.workernotfound.payment.domain.refund.entity.*;
import com.workernotfound.payment.domain.refund.repository.*;
import com.workernotfound.payment.global.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예치의 가용 잔액에서 환불 금액을 예약하고 환불을 REQUESTED로 만든다. PG 환불은 호출하지 않는다.
 *
 * <p>잠금 순서는 환불 명령 → 예치다. 신규 잠금·Saga 해제·정산 예약과 같은 예치 행에서 직렬화되므로 같은 금액이 잠금·정산과 환불에
 * 동시에 쓰이지 않는다. 업무 거절은 금액 변경 전에 판단하고 명령에 영속화한다.
 */
@Service
@RequiredArgsConstructor
public class RefundCommandService {
  private final PaymentDepositRepository deposits;
  private final PaymentRefundRepository refunds;
  private final RefundCommandRepository commands;

  @Transactional
  public Outcome request(String key, RefundRequest request) {
    var command = lockCommand(key, request.fingerprintPayload());
    if (command.refundId() != null || command.rejection() != null) return outcome(command);
    try {
      return reserveRefund(key, request);
    } catch (BusinessException exception) {
      return reject(key, exception);
    }
  }

  private Outcome reserveRefund(String key, RefundRequest request) {
    PaymentDeposit deposit = deposits.findByJobPostIdForUpdate(request.jobPostId())
        .orElseThrow(() -> new BusinessException(PaymentErrorCode.DEPOSIT_NOT_FOUND));
    deposit.reserveRefund(request.ownerMemberId(), request.currency(), request.amount());
    PaymentRefund refund = refunds.saveAndFlush(newRefund(request, deposit, depositedOrderId(deposit)));
    commands.history(refund.getId(), null, RefundStatus.REQUESTED.name());
    commands.complete(key, refund.getId());
    return new Outcome(refund.getId(), null);
  }

  // 차단되지 않은 예치는 DEPOSITED 주문 하나에서만 생긴다. 없거나 여러 개면 원장 불일치이므로 전체를 롤백한다.
  private String depositedOrderId(PaymentDeposit deposit) {
    List<String> orderIds = commands.findDepositedOrderIds(deposit.getJobPostId());
    if (orderIds.size() != 1) throw new IllegalStateException("예치를 만든 결제 주문을 특정할 수 없습니다.");
    return orderIds.get(0);
  }

  public record Outcome(Long refundId, PaymentErrorCode rejection) {}

  private Outcome outcome(RefundCommandRepository.Command command) {
    return new Outcome(command.refundId(), command.rejection() == null
        ? null : PaymentErrorCode.valueOf(command.rejection()));
  }

  // 잔액 부족으로 거절된 명령이 나중에 잔액이 늘었다고 같은 키로 성공하지 않게 거절을 남긴다.
  private Outcome reject(String key, BusinessException exception) {
    PaymentErrorCode rejection = (PaymentErrorCode) exception.getErrorCode();
    commands.reject(key, rejection.name());
    return new Outcome(null, rejection);
  }

  private PaymentRefund newRefund(RefundRequest request, PaymentDeposit deposit, String orderId) {
    return PaymentRefund.builder().depositId(deposit.getId()).orderId(orderId)
        .jobPostId(deposit.getJobPostId()).ownerMemberId(deposit.getOwnerMemberId())
        .amount(request.amount()).currency(deposit.getCurrency()).reason(request.reason()).build();
  }

  private RefundCommandRepository.Command lockCommand(String key, String payload) {
    String fingerprint = fingerprint(payload);
    var command = commands.lockCommand(key, fingerprint);
    if (!command.fingerprint().equals(fingerprint))
      throw new BusinessException(PaymentErrorCode.COMMAND_CONFLICT);
    return command;
  }

  private String fingerprint(String payload) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
    }
  }
}
