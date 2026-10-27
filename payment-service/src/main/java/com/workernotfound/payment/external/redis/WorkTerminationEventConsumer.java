package com.workernotfound.payment.external.redis;

import com.workernotfound.payment.domain.settlement.service.WorkTerminationService;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@ConditionalOnProperty(name = "payment.work-termination.enabled", havingValue = "true", matchIfMissing = true)
public class WorkTerminationEventConsumer extends SettlementStreamConsumer {
  static final String GROUP = "payment-work-termination-v1";
  private final WorkTerminationEventParser parser;
  private final WorkTerminationService service;

  public WorkTerminationEventConsumer(StringRedisTemplate redis, SettlementEventProperties properties,
      WorkTerminationEventParser parser, WorkTerminationService service) {
    super(redis, properties.workStream(), GROUP, "payment-work-termination", properties.batchSize());
    this.parser = parser;
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${payment.work-termination.poll-delay-ms:1000}",
      initialDelayString = "${payment.work-termination.initial-delay-ms:0}")
  public void poll() { pollStream(); }

  @Override boolean isTarget(Map<Object, Object> fields) { return parser.isTarget(fields); }

  @Override void process(RecordId recordId, Map<Object, Object> fields) {
    String result = service.receive(parser.parse(fields));
    if (result.startsWith("REJECTED") || result.equals("REVIEW_REQUIRED"))
      log.warn("근무 종료 금액 처리 확인 필요: recordId={}, result={}", recordId, result);
  }
}
