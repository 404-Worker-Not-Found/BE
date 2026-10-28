package com.workernotfound.payment.domain.payout.service;

import com.workernotfound.payment.domain.payout.repository.PayoutRepository;
import com.workernotfound.payment.domain.payout.repository.PayoutRepository.Claim;
import com.workernotfound.payment.external.payout.PayoutGateway;
import java.time.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.payout.enabled", havingValue = "true")
public class PayoutExecutionService {
  private final PayoutRepository commands;
  private final PayoutTransactionService transactions;
  private final PayoutGateway gateway;
  private final Clock payoutClock;
  @Value("${payment.payout.schedule-zone:Asia/Seoul}") private ZoneId scheduleZone;

  public void execute(long id) {
    Claim claim;
    try { claim = prepare(id); }
    catch (RuntimeException exception) {
      commands.deferPreparation(id, utcNow());
      log.warn("지급 준비 재시도 예약: settlementId={}, type={}", id, exception.getClass().getSimpleName());
      return;
    }
    if (claim == null) return;
    try {
      if (claim.firstAttempt()) transactions.settle(claim, gateway.request(claim.request()));
      else {
        var result = gateway.lookup(claim.request().reference());
        if (result.isPresent()) transactions.settle(claim, result.get());
        else transactions.retry(claim, true);
      }
    } catch (RuntimeException exception) {
      log.warn("지급 결과 미확정: settlementId={}, type={}", id, exception.getClass().getSimpleName());
      transactions.retry(claim, false);
    }
  }

  private Claim prepare(long id) {
    var references = commands.findReferences(id);
    if (references.isEmpty()) return null;
    boolean hasCommand = commands.hasCommand(id);
    if (!hasCommand && !commands.isPreparationDue(id,
        LocalDateTime.ofInstant(payoutClock.instant(), scheduleZone), utcNow())) return null;
    String recipient = hasCommand ? null : gateway.resolveRecipient(references.get().workerId());
    return transactions.claim(references.get(), recipient);
  }

  private LocalDateTime utcNow() { return LocalDateTime.ofInstant(payoutClock.instant(), ZoneOffset.UTC); }

  public void executeDue(int limit) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("지급 조회 크기는 1~100이어야 합니다.");
    var instant = payoutClock.instant();
    for (long id : commands.findDue(LocalDateTime.ofInstant(instant, scheduleZone),
        LocalDateTime.ofInstant(instant, ZoneOffset.UTC), limit)) {
      try { execute(id); }
      catch (RuntimeException exception) {
        log.warn("지급 점유 실패: settlementId={}, type={}", id, exception.getClass().getSimpleName());
      }
    }
  }
}
