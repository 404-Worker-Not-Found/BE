package com.workernotfound.payment.external.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.payment.domain.settlement.exception.InvalidSettlementEventException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * 발행 서비스 Outbox relay가 문자열로 넣은 Stream envelope 필드를 해석한다. 해석할 수 없으면
 * {@link InvalidSettlementEventException}을 던진다.
 */
final class StreamEnvelope {
  static final int SUPPORTED_VERSION = 1;
  // 수신 기록 컬럼(VARCHAR(128) ascii)에 저장할 수 있는 값만 받는다.
  private static final Pattern EVENT_ID_PATTERN = Pattern.compile("[!-~]{1,128}");

  private StreamEnvelope() {}

  static String requireEventId(Object value, String eventType) {
    if (value instanceof String eventId && EVENT_ID_PATTERN.matcher(eventId).matches()) return eventId;
    throw new InvalidSettlementEventException(eventType + " eventId가 없거나 형식이 잘못되었습니다.");
  }

  static long requirePositiveLong(Object value, String name, String eventType) {
    String message = "%s %s가 양의 정수가 아닙니다.".formatted(eventType, name);
    try {
      long parsed = Long.parseLong(String.valueOf(value));
      if (parsed <= 0) throw new InvalidSettlementEventException(message);
      return parsed;
    } catch (NumberFormatException exception) {
      throw new InvalidSettlementEventException(message, exception);
    }
  }

  // 새 스키마 버전은 해석 규칙이 정해질 때까지 pending으로 남긴다.
  static void requireSupportedVersion(Object value, String eventType) {
    if (!String.valueOf(SUPPORTED_VERSION).equals(value))
      throw new InvalidSettlementEventException("지원하지 않는 " + eventType + " 스키마 버전입니다.");
  }

  // 발생 시각이 아예 없으면 거절 결과로 기록할 수 있도록 null을 돌려준다. 값이 있는데 해석할 수 없으면 계약 위반이다.
  static LocalDateTime parseOccurredAt(Object value, String eventType) {
    if (value == null) return null;
    try {
      return LocalDateTime.parse(String.valueOf(value));
    } catch (DateTimeParseException exception) {
      throw new InvalidSettlementEventException(eventType + " occurredAt 형식이 잘못되었습니다.", exception);
    }
  }

  // Jackson 오류 메시지에는 payload 일부가 담길 수 있어 원인 예외로만 보존한다.
  static <T> T readPayload(ObjectMapper mapper, Object value, Class<T> type, String eventType) {
    if (!(value instanceof String json))
      throw new InvalidSettlementEventException(eventType + " payload가 없습니다.");
    try {
      T payload = mapper.readValue(json, type);
      if (payload == null) throw new InvalidSettlementEventException(eventType + " payload가 비어 있습니다.");
      return payload;
    } catch (JsonProcessingException exception) {
      throw new InvalidSettlementEventException(eventType + " payload를 해석할 수 없습니다.", exception);
    }
  }

  // Outbox의 DATETIME(6) 저장은 payload의 마이크로초 미만을 절삭하거나 반올림할 수 있다. 그 차이만 같은 발생 시각으로 본다.
  static boolean isSameOccurrence(LocalDateTime envelopeTime, LocalDateTime payloadTime) {
    if (envelopeTime == null || payloadTime == null) return envelopeTime == null && payloadTime == null;
    if (envelopeTime.equals(payloadTime)) return true;
    LocalDateTime truncated = payloadTime.truncatedTo(ChronoUnit.MICROS);
    boolean hasSubMicros = !payloadTime.equals(truncated);
    return hasSubMicros && (envelopeTime.equals(truncated) || envelopeTime.equals(truncated.plusNanos(1_000)));
  }
}
