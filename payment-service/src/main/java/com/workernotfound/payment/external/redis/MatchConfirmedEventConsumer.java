package com.workernotfound.payment.external.redis;

import com.workernotfound.payment.domain.settlement.service.MatchConfirmation;
import com.workernotfound.payment.domain.settlement.service.MatchConfirmedReceiptService;
import com.workernotfound.payment.domain.settlement.service.SettlementEventOutcome;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code matching:domain-events}의 MatchConfirmed를 payment-service 전용 그룹으로 소비해 정산 대상을 기록한다.
 * ACK·pending 규칙은 {@link SettlementStreamConsumer}를 따른다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.settlement-events.enabled", havingValue = "true", matchIfMissing = true)
public class MatchConfirmedEventConsumer extends SettlementStreamConsumer {
  static final String GROUP = "payment-settlement-matching-v1";
  // 여러 인스턴스가 하나의 논리 소비자를 공유한다. 어느 인스턴스든 pending을 복구할 수 있다.
  static final String CONSUMER = "payment-settlement-matching";

  private final MatchConfirmedEventParser parser;
  private final MatchConfirmedReceiptService receipts;

  public MatchConfirmedEventConsumer(StringRedisTemplate redis, MatchConfirmedEventParser parser,
      MatchConfirmedReceiptService receipts, SettlementEventProperties properties) {
    super(redis, properties.matchingStream(), GROUP, CONSUMER, properties.batchSize());
    this.parser = parser;
    this.receipts = receipts;
  }

  @Scheduled(fixedDelayString = "${payment.settlement-events.poll-interval}",
      initialDelayString = "${payment.settlement-events.initial-delay}")
  public void poll() {
    pollStream();
  }

  @Override
  boolean isTarget(Map<Object, Object> fields) {
    return parser.isTarget(fields);
  }

  @Override
  void process(RecordId recordId, Map<Object, Object> fields) {
    MatchConfirmation confirmation = parser.parse(fields);
    SettlementEventOutcome outcome = receipts.receive(confirmation);
    if (outcome.isRejected())
      log.error("MatchConfirmed 정산 대상 거절을 기록하고 ACK합니다: recordId={}, eventId={}, matchingId={}, result={},"
          + " duplicate={}", recordId, confirmation.eventId(), confirmation.matchingId(), outcome.result(),
          outcome.isDuplicate());
  }
}
