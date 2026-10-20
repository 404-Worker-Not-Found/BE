package com.workernotfound.payment.domain.settlement.service;

import static com.workernotfound.payment.domain.settlement.service.SettlementTargetResult.*;

import com.workernotfound.payment.domain.payment.entity.PaymentLock;
import com.workernotfound.payment.domain.payment.repository.PaymentCommandRepository;
import com.workernotfound.payment.domain.payment.repository.PaymentLockRepository;
import com.workernotfound.payment.domain.settlement.entity.SettlementTarget;
import com.workernotfound.payment.domain.settlement.repository.SettlementTargetRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MatchConfirmed의 확정 사실을 payment-service 잠금과 대조해 정산 대상으로 기록한다. 금액은 옮기지 않는다.
 *
 * <p>확정된 결제 ID가 매칭 slot의 활성 잠금이어야 한다. 이전 Saga 시도에서 해제된 잠금이나 다른 잠금을 가리키면 거절한다.
 * 매칭 slot을 먼저 잠가 Saga 해제·정산 예약과 직렬화한다. 호출자의 트랜잭션에 참여한다.
 */
@Service
@RequiredArgsConstructor
public class SettlementTargetService {
  private final PaymentCommandRepository commands;
  private final PaymentLockRepository payments;
  private final SettlementTargetRepository targets;

  @Transactional
  public SettlementTargetResult record(MatchConfirmation confirmation) {
    if (!confirmation.isComplete()) return INVALID_CONFIRMATION;
    Optional<Long> activePaymentId = commands.findActivePaymentForUpdate(confirmation.matchingId());
    Optional<SettlementTarget> existing = targets.findById(confirmation.matchingId());
    if (existing.isPresent()) return replay(existing.get(), confirmation);
    if (!confirmation.paymentLockId().equals(activePaymentId.orElse(null))) return LOCK_NOT_FOUND;
    PaymentLock lock = payments.findById(confirmation.paymentLockId())
        .orElseThrow(() -> new IllegalStateException("매칭 slot의 활성 잠금이 없습니다."));
    if (!isSameLock(lock, confirmation)) return LOCK_MISMATCH;
    if (targets.existsByWorkId(confirmation.workId())) return TARGET_CONFLICT;
    targets.saveAndFlush(newTarget(confirmation));
    return RECORDED;
  }

  private SettlementTargetResult replay(SettlementTarget target, MatchConfirmation confirmation) {
    boolean isSame = target.hasSameConfirmation(confirmation.workId(), confirmation.paymentLockId(),
        confirmation.jobPostId(), confirmation.ownerMemberId(), confirmation.workerMemberId(),
        confirmation.lockedAmount(), confirmation.currency());
    return isSame ? ALREADY_RECORDED : TARGET_CONFLICT;
  }

  private boolean isSameLock(PaymentLock lock, MatchConfirmation confirmation) {
    return lock.getMatchingId().equals(confirmation.matchingId())
        && lock.getJobPostId().equals(confirmation.jobPostId())
        && lock.getOwnerMemberId().equals(confirmation.ownerMemberId())
        && lock.getWorkerMemberId().equals(confirmation.workerMemberId())
        && lock.getAmount().compareTo(confirmation.lockedAmount()) == 0
        && lock.getCurrency().equals(confirmation.currency());
  }

  private SettlementTarget newTarget(MatchConfirmation confirmation) {
    return SettlementTarget.builder().matchingId(confirmation.matchingId()).workId(confirmation.workId())
        .paymentLockId(confirmation.paymentLockId()).jobPostId(confirmation.jobPostId())
        .ownerMemberId(confirmation.ownerMemberId()).workerMemberId(confirmation.workerMemberId())
        .lockedAmount(confirmation.lockedAmount()).currency(confirmation.currency())
        .matchingRevision(confirmation.revision()).sourceEventId(confirmation.eventId())
        .confirmedAt(confirmation.confirmedAt()).build();
  }
}
