package com.workernotfound.payment.domain.settlement.service;

import com.workernotfound.payment.domain.payment.entity.*;
import com.workernotfound.payment.domain.payment.repository.*;
import com.workernotfound.payment.domain.settlement.entity.*;
import com.workernotfound.payment.domain.settlement.exception.SettlementTargetNotReadyException;
import com.workernotfound.payment.domain.settlement.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class WorkTerminationService {
  private final WorkTerminationRepository receipts;
  private final SettlementTargetRepository targets;
  private final PaymentCommandRepository commands;
  private final PaymentDepositRepository deposits;
  private final PaymentLockRepository locks;
  private final SettlementRepository settlements;
  private final SettlementHistoryRepository histories;
  private final MatchingEventReceiptRepository matchingReceipts;

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public String receive(WorkTerminationEvent event) {
    if (!event.isValid() || event.isCompleted()) throw new IllegalArgumentException("잘못된 취소·노쇼 이벤트입니다.");
    var receipt = receipts.findReceipt(event.eventId());
    if (receipt.isPresent()) {
      if (!receipt.get().fingerprint().equals(event.fingerprint())) throw new IllegalArgumentException("같은 eventId의 내용이 다릅니다.");
      return receipt.get().result();
    }
    var activePayment = commands.findActivePaymentForUpdate(event.matchingId());
    var target = targets.findById(event.matchingId());
    if (target.isEmpty()) {
      if (matchingReceipts.existsRejectedByMatchingId(event.matchingId())) return receipts.recordReceipt(event, "REJECTED_CONFIRMATION");
      throw new SettlementTargetNotReadyException("선행 매칭 확정을 기다립니다.");
    }
    if (target.get().getWorkId() != event.workId() || !target.get().hasSameParticipants(event.jobPostId(), event.ownerMemberId(), event.workerMemberId()))
      return receipts.recordReceipt(event, "REJECTED_WORK_MISMATCH");
    var existing = receipts.findDecision(event.workId());
    if (existing.isPresent()) return receipts.recordReceipt(event,
        existing.get().fingerprint().equals(event.factFingerprint()) ? existing.get().result() : "REJECTED_WORK_CONFLICT");
    var deposit = deposits.findByJobPostIdForUpdate(event.jobPostId()).orElseThrow();
    var lock = locks.findByIdForUpdate(target.get().getPaymentLockId()).orElseThrow();
    receipts.insertDecision(event, lock.getId());
    String result = release(event, deposit, lock, activePayment.orElse(null));
    return receipts.recordReceipt(event, result);
  }

  private String release(WorkTerminationEvent event, PaymentDeposit deposit, PaymentLock lock, Long activePayment) {
    if (lock.getStatus() == PaymentLock.Status.RELEASED) return receipts.decide(event.workId(), "ALREADY_RELEASED", null);
    if (lock.getStatus() == PaymentLock.Status.TRANSFERRED) {
      holdSettlement(event.workId());
      return receipts.decide(event.workId(), "REVIEW_REQUIRED", "TRANSFERRED_TO_SETTLEMENT");
    }
    if (deposit.isFundingBlocked()) return receipts.decide(event.workId(), "REVIEW_REQUIRED", "FUNDING_BLOCKED");
    if (!lock.getId().equals(activePayment)) return receipts.decide(event.workId(), "REVIEW_REQUIRED", "INACTIVE_LOCK");
    lock.release();
    deposit.release(lock.getAmount());
    commands.release(lock.getMatchingId(), lock.getId());
    receipts.releaseHistory(lock.getId(), event.workId());
    return receipts.decide(event.workId(), "RELEASED", null);
  }

  private void holdSettlement(long workId) {
    settlements.findByWorkId(workId).ifPresent(settlement -> {
      if (settlement.getStatus() == SettlementStatus.RESERVED || settlement.getStatus() == SettlementStatus.PAYOUT_FAILED) {
        String previous = settlement.getStatus().name();
        settlement.hold(SettlementHoldReason.WORK_TERMINATED);
        histories.record(settlement.getId(), previous, SettlementStatus.ON_HOLD.name(), "WORK_TERMINATED");
      }
    });
  }
}
