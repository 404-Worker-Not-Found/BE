package com.workernotfound.payment.domain.settlement.service;

import com.workernotfound.payment.domain.settlement.exception.InvalidSettlementEventException;
import com.workernotfound.payment.domain.settlement.repository.WorkEventReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * WorkCompleted를 eventId 기준으로 한 번만 처리한다. 정산 생성·금액 이관과 수신 기록·결과를 한 트랜잭션에 저장한다.
 *
 * <p>같은 eventId의 동시 처리는 매칭 slot 잠금과 eventId 유일 제약으로 막는다. 늦은 처리는 {@code ALREADY_RESERVED}를 얻은 뒤
 * 수신 기록 저장에서 롤백되고, 다음 pending 재수신에서 중복으로 판단된다. eventId가 다른 같은 근무 완료는 정산의 근무 유일 제약과
 * 재요청 비교로 {@code ALREADY_RESERVED}가 된다. 이미 진행된 정산(보류·지급 등)의 상태는 바꾸지 않는다.
 *
 * <p>DB 장애 같은 일시 오류와 선행 MatchConfirmed 미도착은 예외로 전파되어 아무것도 기록하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class WorkCompletedReceiptService {
  private final WorkEventReceiptRepository receipts;
  private final SettlementReservationService reservations;

  @Transactional
  public SettlementEventOutcome receive(WorkCompletion completion, long revision) {
    String fingerprint = completion.fingerprint(revision);
    var receipt = receipts.findByEventId(completion.eventId());
    if (receipt.isPresent()) return replay(receipt.get(), fingerprint);
    SettlementReservationResult result = reservations.reserve(completion);
    receipts.save(completion.eventId(), completion.workId(), completion.matchingId(), revision, fingerprint,
        result.outcome().name(), result.settlementId());
    return new SettlementEventOutcome(result.outcome().name(), result.settlementId(), result.isRejected(), false);
  }

  private SettlementEventOutcome replay(WorkEventReceiptRepository.Receipt receipt, String fingerprint) {
    if (!receipt.fingerprint().equals(fingerprint))
      throw new InvalidSettlementEventException("같은 eventId에 다른 내용의 WorkCompleted가 전달되었습니다.");
    var result = new SettlementReservationResult(
        SettlementReservationResult.Outcome.valueOf(receipt.result()), receipt.settlementId());
    return new SettlementEventOutcome(receipt.result(), receipt.settlementId(), result.isRejected(), true);
  }
}
