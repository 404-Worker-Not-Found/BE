package com.workernotfound.payment.external.redis;

import static com.workernotfound.payment.external.redis.StreamEnvelope.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.payment.domain.settlement.exception.InvalidSettlementEventException;
import com.workernotfound.payment.domain.settlement.service.WorkCompletion;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * work-service가 {@code work:domain-events}에 발행한 WorkCompleted 레코드를 {@link WorkCompletion}으로 해석한다.
 *
 * <p>envelope 필드는 work-service {@code WorkEventPublisher}가 문자열로 넣는다. 근무별 revision은 출근 1, 시작 2, 완료 3으로
 * 고정이므로 WorkCompleted는 revision 3, 상태 {@code COMPLETED}여야 한다. envelope와 payload가 맞지 않거나 해석할 수 없으면
 * {@link InvalidSettlementEventException}을 던진다. 매칭·공고·참여자 ID가 비어 있는 것은 여기서 거절하지 않고 거절 결과로 기록한다.
 */
@Component
@RequiredArgsConstructor
public class WorkCompletedEventParser {
  static final String EVENT_TYPE = "WorkCompleted";
  static final String AGGREGATE_TYPE = "WORK";
  static final long COMPLETED_REVISION = 3;
  private static final String COMPLETED_STATUS = "COMPLETED";

  private final ObjectMapper mapper;

  public boolean isTarget(Map<Object, Object> fields) {
    return EVENT_TYPE.equals(fields.get("eventType")) && AGGREGATE_TYPE.equals(fields.get("aggregateType"));
  }

  public WorkCompletion parse(Map<Object, Object> fields) {
    String eventId = requireEventId(fields.get("eventId"), EVENT_TYPE);
    long aggregateId = requirePositiveLong(fields.get("aggregateId"), "aggregateId", EVENT_TYPE);
    long revision = requirePositiveLong(fields.get("revision"), "revision", EVENT_TYPE);
    requireSupportedVersion(fields.get("version"), EVENT_TYPE);
    LocalDateTime occurredAt = parseOccurredAt(fields.get("occurredAt"), EVENT_TYPE);
    WorkCompletedPayload payload = readPayload(mapper, fields.get("payload"), WorkCompletedPayload.class, EVENT_TYPE);
    if (!hasMatchingEnvelope(payload, eventId, aggregateId, revision)
        || !isSameOccurrence(occurredAt, payload.occurredAt()))
      throw new InvalidSettlementEventException("WorkCompleted envelope와 payload가 일치하지 않습니다.");
    if (revision != COMPLETED_REVISION || !COMPLETED_STATUS.equals(payload.status()))
      throw new InvalidSettlementEventException("WorkCompleted revision 또는 상태가 계약과 다릅니다.");
    return new WorkCompletion(eventId, aggregateId, payload.matchingId(), payload.jobPostId(),
        payload.ownerMemberId(), payload.workerMemberId(), occurredAt);
  }

  private boolean hasMatchingEnvelope(WorkCompletedPayload payload, String eventId, long aggregateId,
      long revision) {
    return eventId.equals(payload.eventId()) && EVENT_TYPE.equals(payload.eventType())
        && Objects.equals(aggregateId, payload.aggregateId())
        && Objects.equals(aggregateId, payload.workId())
        && Objects.equals(revision, payload.revision())
        && Objects.equals(SUPPORTED_VERSION, payload.version());
  }
}
