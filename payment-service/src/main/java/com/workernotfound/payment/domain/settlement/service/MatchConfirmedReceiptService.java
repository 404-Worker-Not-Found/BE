package com.workernotfound.payment.domain.settlement.service;

import com.workernotfound.payment.domain.settlement.exception.InvalidSettlementEventException;
import com.workernotfound.payment.domain.settlement.repository.MatchingEventReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * MatchConfirmed를 eventId 기준으로 한 번만 처리한다. 정산 대상 기록과 수신 기록·결과를 한 트랜잭션에 저장한다.
 *
 * <p>같은 eventId의 동시 처리는 매칭 slot 잠금과 eventId 유일 제약으로 막는다. 늦은 처리는 {@code ALREADY_RECORDED}를 얻은 뒤
 * 수신 기록 저장에서 롤백되고, 다음 pending 재수신에서 중복으로 판단된다.
 *
 * <p>근무 완료 수신과 같이 READ COMMITTED로 실행해 slot 잠금 뒤의 조회가 동시에 커밋된 정산 대상을 보게 한다.
 */
@Service
@RequiredArgsConstructor
public class MatchConfirmedReceiptService {
  private final MatchingEventReceiptRepository receipts;
  private final SettlementTargetService targets;

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public SettlementEventOutcome receive(MatchConfirmation confirmation) {
    String fingerprint = confirmation.fingerprint();
    var receipt = receipts.findByEventId(confirmation.eventId());
    if (receipt.isPresent()) return replay(receipt.get(), fingerprint);
    SettlementTargetResult result = targets.record(confirmation);
    receipts.save(confirmation.eventId(), confirmation.matchingId(), confirmation.revision(), fingerprint,
        result.name());
    return new SettlementEventOutcome(result.name(), null, result.isRejected(), false);
  }

  private SettlementEventOutcome replay(MatchingEventReceiptRepository.Receipt receipt, String fingerprint) {
    if (!receipt.fingerprint().equals(fingerprint))
      throw new InvalidSettlementEventException("같은 eventId에 다른 내용의 MatchConfirmed가 전달되었습니다.");
    SettlementTargetResult result = SettlementTargetResult.valueOf(receipt.result());
    return new SettlementEventOutcome(result.name(), null, result.isRejected(), true);
  }
}
