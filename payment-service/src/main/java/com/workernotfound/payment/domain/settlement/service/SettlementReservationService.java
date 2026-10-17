package com.workernotfound.payment.domain.settlement.service;

import static com.workernotfound.payment.domain.settlement.service.SettlementReservationResult.Outcome.*;

import com.workernotfound.payment.domain.payment.entity.PaymentDeposit;
import com.workernotfound.payment.domain.payment.entity.PaymentLock;
import com.workernotfound.payment.domain.payment.repository.*;
import com.workernotfound.payment.domain.settlement.entity.*;
import com.workernotfound.payment.domain.settlement.repository.*;
import java.math.BigDecimal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 근무 완료 사실로 매칭 잠금 금액 전액을 정산 예약으로 옮긴다. 외부 지급은 호출하지 않는다.
 *
 * <p>잠금 순서는 Saga 해제와 같은 매칭 slot → 예치 → 잠금이다. 정산 예약·Saga 해제·신규 잠금은 같은 예치 행에서 직렬화된다.
 * 확정 결과는 반환하고 DB·잠금 실패는 예외로 전파해 호출자가 같은 사실로 재시도한다. 호출자의 트랜잭션에 참여한다.
 */
@Service
@RequiredArgsConstructor
public class SettlementReservationService {
  private static final String SETTLEMENT_CURRENCY = "KRW";

  private final PaymentCommandRepository commands;
  private final PaymentLockRepository payments;
  private final PaymentDepositRepository deposits;
  private final SettlementRepository settlements;
  private final SettlementHistoryRepository histories;
  private final SettlementProperties properties;

  @Transactional
  public SettlementReservationResult reserve(WorkCompletion completion) {
    if (!completion.isComplete()) return SettlementReservationResult.rejected(INVALID_COMPLETION);
    Optional<Long> activePaymentId = commands.findActivePaymentForUpdate(completion.matchingId());
    Optional<Settlement> existing = settlements.findByWorkId(completion.workId());
    if (existing.isPresent()) return replay(existing.get(), completion);
    if (activePaymentId.isEmpty()) return SettlementReservationResult.rejected(LOCK_NOT_FOUND);
    return transfer(activePaymentId.get(), completion);
  }

  private SettlementReservationResult replay(Settlement settlement, WorkCompletion completion) {
    if (!settlement.hasSameCompletion(completion.matchingId(), completion.jobPostId(),
        completion.ownerMemberId(), completion.workerMemberId(), completion.completedAt()))
      return SettlementReservationResult.rejected(WORK_CONFLICT);
    return new SettlementReservationResult(ALREADY_RESERVED, settlement.getId());
  }

  private SettlementReservationResult transfer(Long paymentId, WorkCompletion completion) {
    var references = payments.findReferencesById(paymentId)
        .orElseThrow(() -> new IllegalStateException("매칭 slot의 활성 잠금이 없습니다."));
    PaymentDeposit deposit = deposits.findByJobPostIdForUpdate(references.getJobPostId())
        .orElseThrow(() -> new IllegalStateException("잠금의 예치가 없습니다."));
    PaymentLock lock = payments.findByIdForUpdate(paymentId)
        .orElseThrow(() -> new IllegalStateException("매칭 slot의 활성 잠금이 없습니다."));
    if (!isSameParticipants(lock, completion)) return SettlementReservationResult.rejected(LOCK_MISMATCH);
    // 활성 잠금이 LOCKED가 아니면 같은 매칭의 다른 근무가 이미 정산으로 가져갔다.
    if (!lock.isLocked()) return SettlementReservationResult.rejected(WORK_CONFLICT);
    if (!isSupportedAmount(lock)) return SettlementReservationResult.rejected(UNSUPPORTED_AMOUNT);
    Settlement settlement = reserveAmount(deposit, lock, completion);
    if (deposit.isFundingBlocked()) hold(settlement, SettlementHoldReason.FUNDING_BLOCKED);
    return new SettlementReservationResult(RESERVED, settlement.getId());
  }

  private Settlement reserveAmount(PaymentDeposit deposit, PaymentLock lock, WorkCompletion completion) {
    lock.transferToSettlement();
    deposit.transferToSettlement(lock.getAmount(), lock.getAmount());
    Settlement settlement = settlements.saveAndFlush(newSettlement(deposit, lock, completion));
    commands.settlementHistory(lock.getId(), settlement.getId());
    histories.record(settlement.getId(), null, SettlementStatus.RESERVED.name(), null);
    return settlement;
  }

  private void hold(Settlement settlement, SettlementHoldReason reason) {
    settlement.hold(reason);
    histories.record(settlement.getId(), SettlementStatus.RESERVED.name(),
        SettlementStatus.ON_HOLD.name(), reason.name());
  }

  private boolean isSameParticipants(PaymentLock lock, WorkCompletion completion) {
    return lock.getMatchingId().equals(completion.matchingId())
        && lock.getJobPostId().equals(completion.jobPostId())
        && lock.getOwnerMemberId().equals(completion.ownerMemberId())
        && lock.getWorkerMemberId().equals(completion.workerMemberId());
  }

  // 정산은 정수 KRW만 예약한다. 원 미만 금액을 반올림해 예약하지 않는다.
  private boolean isSupportedAmount(PaymentLock lock) {
    return SETTLEMENT_CURRENCY.equals(lock.getCurrency())
        && lock.getAmount().remainder(BigDecimal.ONE).signum() == 0;
  }

  private Settlement newSettlement(PaymentDeposit deposit, PaymentLock lock, WorkCompletion completion) {
    return Settlement.builder().workId(completion.workId()).paymentLockId(lock.getId())
        .depositId(deposit.getId()).matchingId(lock.getMatchingId()).jobPostId(lock.getJobPostId())
        .ownerMemberId(lock.getOwnerMemberId()).workerMemberId(lock.getWorkerMemberId())
        .sourceAmount(lock.getAmount()).currency(lock.getCurrency())
        .sourceEventId(completion.eventId()).workCompletedAt(completion.completedAt())
        .payableAt(completion.completedAt().plus(properties.payoutDelay())).build();
  }
}
