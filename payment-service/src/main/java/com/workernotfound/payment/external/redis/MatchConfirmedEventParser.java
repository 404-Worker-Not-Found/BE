package com.workernotfound.payment.external.redis;

import static com.workernotfound.payment.external.redis.StreamEnvelope.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.payment.domain.settlement.exception.InvalidSettlementEventException;
import com.workernotfound.payment.domain.settlement.service.MatchConfirmation;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * matching-service가 {@code matching:domain-events}에 발행한 MatchConfirmed 레코드를 {@link MatchConfirmation}으로 해석한다.
 *
 * <p>envelope 필드는 matching-service {@code RedisStreamOutboxEventPublisher}가 문자열로 넣고, {@code occurredAt}은 Outbox의
 * {@code DATETIME(6)} 저장값이다. 대상 판별은 envelope의 이벤트 종류와 aggregateType만으로 한다. envelope와 payload가 맞지 않거나
 * 해석할 수 없으면 {@link InvalidSettlementEventException}을 던진다. 근무·결제 ID처럼 업무 값이 비어 있거나 숫자가 아닌 것은 여기서
 * 거절하지 않고 null로 넘겨 거절 결과로 기록한다.
 */
@Component
@RequiredArgsConstructor
public class MatchConfirmedEventParser {
  static final String EVENT_TYPE = "MatchConfirmed";
  static final String AGGREGATE_TYPE = "MATCHING";
  private static final Pattern NUMERIC_ID = Pattern.compile("[1-9][0-9]{0,18}");

  private final ObjectMapper mapper;

  public boolean isTarget(Map<Object, Object> fields) {
    return EVENT_TYPE.equals(fields.get("eventType")) && AGGREGATE_TYPE.equals(fields.get("aggregateType"));
  }

  public MatchConfirmation parse(Map<Object, Object> fields) {
    String eventId = requireEventId(fields.get("eventId"), EVENT_TYPE);
    long aggregateId = requirePositiveLong(fields.get("aggregateId"), "aggregateId", EVENT_TYPE);
    long revision = requirePositiveLong(fields.get("revision"), "revision", EVENT_TYPE);
    requireSupportedVersion(fields.get("version"), EVENT_TYPE);
    LocalDateTime occurredAt = parseOccurredAt(fields.get("occurredAt"), EVENT_TYPE);
    MatchConfirmedPayload payload = readPayload(mapper, fields.get("payload"), MatchConfirmedPayload.class, EVENT_TYPE);
    if (!hasMatchingEnvelope(payload, eventId, aggregateId, revision)
        || !isSameOccurrence(occurredAt, payload.occurredAt()))
      throw new InvalidSettlementEventException("MatchConfirmed envelope와 payload가 일치하지 않습니다.");
    return new MatchConfirmation(eventId, aggregateId, revision, numericId(payload.workId()),
        numericId(payload.paymentId()), payload.jobPostId(), payload.ownerMemberId(), payload.workerMemberId(),
        payload.lockedAmount(), payload.currency(), occurredAt);
  }

  private boolean hasMatchingEnvelope(MatchConfirmedPayload payload, String eventId, long aggregateId,
      long revision) {
    return eventId.equals(payload.eventId()) && EVENT_TYPE.equals(payload.eventType())
        && Objects.equals(aggregateId, payload.aggregateId())
        && Objects.equals(aggregateId, payload.matchingId())
        && Objects.equals(revision, payload.revision())
        && Objects.equals(SUPPORTED_VERSION, payload.version());
  }

  // 발행 형식은 양의 정수 문자열이다. 범위를 넘거나 숫자가 아니면 거절 결과로 남기도록 null로 바꾼다.
  private Long numericId(String value) {
    if (value == null || !NUMERIC_ID.matcher(value).matches()) return null;
    try {
      return Long.valueOf(value);
    } catch (NumberFormatException exception) {
      return null;
    }
  }
}
