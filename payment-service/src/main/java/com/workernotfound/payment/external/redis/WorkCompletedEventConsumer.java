package com.workernotfound.payment.external.redis;

import com.workernotfound.payment.domain.settlement.service.SettlementEventOutcome;
import com.workernotfound.payment.domain.settlement.service.WorkCompletedReceiptService;
import com.workernotfound.payment.domain.settlement.service.WorkCompletion;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code work:domain-events}의 WorkCompleted를 payment-service 전용 그룹으로 소비해 정산을 예약한다. 외부 지급은 실행하지 않는다.
 * 출근·시작 같은 다른 근무 이벤트는 정산과 관계없으므로 ACK한다. ACK·pending 규칙은 {@link SettlementStreamConsumer}를 따른다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.settlement-events.enabled", havingValue = "true", matchIfMissing = true)
public class WorkCompletedEventConsumer extends SettlementStreamConsumer {
  static final String GROUP = "payment-settlement-work-v1";
  // 여러 인스턴스가 하나의 논리 소비자를 공유한다. 어느 인스턴스든 pending을 복구할 수 있다.
  static final String CONSUMER = "payment-settlement-work";

  private final WorkCompletedEventParser parser;
  private final WorkCompletedReceiptService receipts;

  public WorkCompletedEventConsumer(StringRedisTemplate redis, WorkCompletedEventParser parser,
      WorkCompletedReceiptService receipts, SettlementEventProperties properties) {
    super(redis, properties.workStream(), GROUP, CONSUMER, properties.batchSize());
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
    WorkCompletion completion = parser.parse(fields);
    SettlementEventOutcome outcome = receipts.receive(completion, WorkCompletedEventParser.COMPLETED_REVISION);
    if (outcome.isRejected())
      log.error("WorkCompleted 정산 거절을 기록하고 ACK합니다: recordId={}, eventId={}, workId={}, result={},"
          + " duplicate={}", recordId, completion.eventId(), completion.workId(), outcome.result(),
          outcome.isDuplicate());
  }
}
