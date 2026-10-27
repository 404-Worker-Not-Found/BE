package com.workernotfound.payment.domain.payout.service;

import com.workernotfound.payment.domain.payment.entity.*;
import com.workernotfound.payment.domain.payment.repository.*;
import com.workernotfound.payment.domain.payout.repository.PayoutRepository;
import com.workernotfound.payment.domain.payout.repository.PayoutRepository.*;
import com.workernotfound.payment.domain.settlement.entity.*;
import com.workernotfound.payment.domain.settlement.repository.*;
import com.workernotfound.payment.external.payout.PayoutGateway.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class PayoutTransactionService {
  private final PayoutRepository commands;
  private final PaymentCommandRepository slots;
  private final PaymentDepositRepository deposits;
  private final PaymentLockRepository locks;
  private final SettlementRepository settlements;
  private final SettlementHistoryRepository histories;
  private final Clock payoutClock;
  @Value("${payment.payout.schedule-zone:Asia/Seoul}") private ZoneId scheduleZone;

  private record Locked(Settlement settlement, PaymentDeposit deposit, boolean active) {}

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Claim claim(References references, String recipient) {
    Locked locked = lock(references);
    Settlement settlement = locked.settlement();
    LocalDateTime now = utcNow();
    var existing = commands.findCommandForUpdate(settlement.getId());
    if (existing.isPresent()) return reclaim(settlement, existing.get(), now);
    if (settlement.getStatus() != SettlementStatus.RESERVED || scheduleNow().isBefore(settlement.getPayableAt())) return null;
    if (!locked.active()) throw new IllegalStateException("정산의 원래 잠금이 활성 TRANSFERRED 상태가 아닙니다.");
    if (locked.deposit().isFundingBlocked()) {
      settlement.hold(SettlementHoldReason.FUNDING_BLOCKED);
      histories.record(settlement.getId(), "RESERVED", "ON_HOLD", "FUNDING_BLOCKED");
      return null;
    }
    if (recipient == null || !recipient.matches("[!-~]{1,128}")) throw new IllegalArgumentException("유효한 수취인 참조가 필요합니다.");
    var request = new Request("payout-" + UUID.randomUUID(), recipient, settlement.getAmount(), settlement.getCurrency());
    String token = UUID.randomUUID().toString();
    settlement.requestPayout(scheduleNow());
    histories.record(settlement.getId(), "RESERVED", "PAYOUT_REQUESTED", "PAYOUT_CLAIMED");
    commands.insert(settlement.getId(), request, token, now);
    return new Claim(settlement.getId(), request, token, true);
  }

  private Claim reclaim(Settlement settlement, Command command, LocalDateTime now) {
    if (settlement.getStatus() != SettlementStatus.PAYOUT_REQUESTED || !command.status().equals("AWAITING_RESULT")
        || command.nextAttempt().isAfter(now) || (command.leaseUntil() != null && command.leaseUntil().isAfter(now))) return null;
    String token = UUID.randomUUID().toString();
    commands.claim(settlement.getId(), token, now);
    return new Claim(settlement.getId(), command.request(), token, false);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void settle(Claim claim, Result result) {
    var references = commands.findReferences(claim.settlementId()).orElseThrow();
    Locked locked = lock(references);
    Command command = commands.findCommandForUpdate(claim.settlementId()).orElseThrow();
    if (!owns(claim, command, locked.settlement())) return;
    if (!matches(command, result)) {
      commands.resolve(claim.settlementId(), "REVIEW_REQUIRED", command.providerId(), "PROVIDER_MISMATCH", utcNow());
      return;
    }
    switch (result.status()) {
      case PENDING -> commands.resolve(claim.settlementId(), "AWAITING_RESULT", result.providerId(), null, utcNow());
      case FAILED -> {
        locked.settlement().failPayout();
        histories.record(claim.settlementId(), "PAYOUT_REQUESTED", "PAYOUT_FAILED", "PROVIDER_FAILED");
        commands.resolve(claim.settlementId(), "FAILED", result.providerId(), null, utcNow());
      }
      case SUCCEEDED -> {
        locked.deposit().completeSettlementPayout(command.request().amount());
        locked.settlement().completePayout(result.paidAt().atZoneSameInstant(scheduleZone).toLocalDateTime());
        histories.record(claim.settlementId(), "PAYOUT_REQUESTED", "PAID", "PROVIDER_SUCCEEDED");
        commands.resolve(claim.settlementId(), "SUCCEEDED", result.providerId(), null, utcNow());
      }
    }
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void retry(Claim claim, boolean missing) {
    Locked locked = lock(commands.findReferences(claim.settlementId()).orElseThrow());
    Command command = commands.findCommandForUpdate(claim.settlementId()).orElseThrow();
    if (owns(claim, command, locked.settlement())) commands.resolve(claim.settlementId(),
        missing ? "REVIEW_REQUIRED" : "AWAITING_RESULT", command.providerId(), missing ? "PROVIDER_RESULT_MISSING" : null, utcNow());
  }

  private Locked lock(References references) {
    var active = slots.findActivePaymentForUpdate(references.matchingId());
    var deposit = deposits.findByJobPostIdForUpdate(references.jobPostId()).orElseThrow();
    // 정산 엔티티를 잠금 전에 읽으면 JPA 1차 캐시가 오래된 상태를 반환할 수 있어 참조만 별도 조회한다.
    long paymentId = settlements.findPaymentLockId(references.settlementId()).orElseThrow();
    var payment = locks.findByIdForUpdate(paymentId).orElseThrow();
    var settlement = settlements.findByIdForUpdate(references.settlementId()).orElseThrow();
    if (!settlement.getDepositId().equals(deposit.getId()) || !settlement.getMatchingId().equals(references.matchingId())
        || !settlement.getWorkerMemberId().equals(references.workerId())) throw new IllegalStateException("정산 참조 불일치");
    return new Locked(settlement, deposit, payment.getStatus() == PaymentLock.Status.TRANSFERRED
        && payment.getId().equals(active.orElse(null)));
  }

  private boolean owns(Claim claim, Command command, Settlement settlement) {
    return settlement.getStatus() == SettlementStatus.PAYOUT_REQUESTED && command.status().equals("AWAITING_RESULT")
        && claim.token().equals(command.token()) && command.leaseUntil() != null && command.leaseUntil().isAfter(utcNow());
  }

  private boolean matches(Command command, Result result) {
    Request expected = command.request();
    return result != null && expected.reference().equals(result.reference()) && expected.recipient().equals(result.recipient())
        && expected.currency().equals(result.currency()) && result.amount() != null
        && expected.amount().compareTo(result.amount()) == 0 && result.status() != null
        && result.providerId() != null && result.providerId().matches("[!-~]{1,128}")
        && (command.providerId() == null || command.providerId().equals(result.providerId()))
        && (result.status() != Status.SUCCEEDED || result.paidAt() != null);
  }

  private LocalDateTime utcNow() { return LocalDateTime.ofInstant(payoutClock.instant(), ZoneOffset.UTC); }
  private LocalDateTime scheduleNow() { return LocalDateTime.ofInstant(payoutClock.instant(), scheduleZone); }
}
