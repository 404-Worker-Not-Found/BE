package com.workernotfound.member.external.redis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.member.domain.review.service.WorkCompletion;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * work-service가 {@code work:domain-events}에 발행한 WorkCompleted 레코드를 {@link WorkCompletion}으로 해석한다.
 *
 * <p>envelope 필드는 work-service {@code WorkEventPublisher}가 문자열로 넣는다. 근무별 revision은 출근 1, 시작 2, 완료 3으로
 * 고정이므로 WorkCompleted는 revision 3, 상태 {@code COMPLETED}여야 한다. envelope와 payload가 맞지 않거나, 참여자·공고 ID가
 * 비어 있거나 같으면 리뷰 대상으로 쓸 수 없으므로 {@link InvalidReviewEventException}을 던진다.
 */
@Component
@RequiredArgsConstructor
public class WorkCompletedEventParser {
	static final String EVENT_TYPE = "WorkCompleted";
	static final String AGGREGATE_TYPE = "WORK";
	private static final long COMPLETED_REVISION = 3;
	private static final String COMPLETED_STATUS = "COMPLETED";
	private static final String SUPPORTED_VERSION = "1";
	// 수신 기록 컬럼(VARCHAR(128) ascii)에 저장할 수 있는 값만 받는다.
	private static final Pattern EVENT_ID_PATTERN = Pattern.compile("[!-~]{1,128}");

	private final ObjectMapper mapper;

	public boolean isTarget(Map<Object, Object> fields) {
		return EVENT_TYPE.equals(fields.get("eventType")) && AGGREGATE_TYPE.equals(fields.get("aggregateType"));
	}

	public WorkCompletion parse(Map<Object, Object> fields) {
		String eventId = requireEventId(fields.get("eventId"));
		long aggregateId = requirePositiveLong(fields.get("aggregateId"), "aggregateId");
		long revision = requirePositiveLong(fields.get("revision"), "revision");
		if (!SUPPORTED_VERSION.equals(fields.get("version")))
			throw new InvalidReviewEventException("지원하지 않는 WorkCompleted 스키마 버전입니다.");
		LocalDateTime occurredAt = parseOccurredAt(fields.get("occurredAt"));
		Payload payload = readPayload(fields.get("payload"));
		if (!eventId.equals(payload.eventId()) || !EVENT_TYPE.equals(payload.eventType())
			|| !Objects.equals(aggregateId, payload.aggregateId()) || !Objects.equals(aggregateId, payload.workId())
			|| !Objects.equals(revision, payload.revision()) || !Objects.equals(1, payload.version())
			|| !isSameOccurrence(occurredAt, payload.occurredAt()))
			throw new InvalidReviewEventException("WorkCompleted envelope와 payload가 일치하지 않습니다.");
		if (revision != COMPLETED_REVISION || !COMPLETED_STATUS.equals(payload.status()))
			throw new InvalidReviewEventException("WorkCompleted revision 또는 상태가 계약과 다릅니다.");
		if (!isPositive(payload.jobPostId()) || !isPositive(payload.ownerMemberId())
			|| !isPositive(payload.workerMemberId()) || payload.ownerMemberId().equals(payload.workerMemberId()))
			throw new InvalidReviewEventException("WorkCompleted 공고·참여자 ID가 리뷰 대상 계약과 다릅니다.");
		return new WorkCompletion(eventId, aggregateId, payload.jobPostId(), payload.ownerMemberId(),
			payload.workerMemberId(), payload.workDate(), occurredAt.truncatedTo(ChronoUnit.MICROS));
	}

	private static String requireEventId(Object value) {
		if (value instanceof String eventId && EVENT_ID_PATTERN.matcher(eventId).matches()) return eventId;
		throw new InvalidReviewEventException("WorkCompleted eventId가 없거나 형식이 잘못되었습니다.");
	}

	private static long requirePositiveLong(Object value, String name) {
		try {
			long parsed = Long.parseLong(String.valueOf(value));
			if (parsed > 0) return parsed;
		} catch (NumberFormatException exception) {
			throw new InvalidReviewEventException("WorkCompleted " + name + "가 양의 정수가 아닙니다.", exception);
		}
		throw new InvalidReviewEventException("WorkCompleted " + name + "가 양의 정수가 아닙니다.");
	}

	private static LocalDateTime parseOccurredAt(Object value) {
		try {
			return LocalDateTime.parse(String.valueOf(value));
		} catch (DateTimeParseException exception) {
			throw new InvalidReviewEventException("WorkCompleted occurredAt 형식이 잘못되었습니다.", exception);
		}
	}

	// Jackson 오류 메시지에는 payload 일부가 담길 수 있어 원인 예외로만 보존한다.
	private Payload readPayload(Object value) {
		if (!(value instanceof String json)) throw new InvalidReviewEventException("WorkCompleted payload가 없습니다.");
		try {
			Payload payload = mapper.readValue(json, Payload.class);
			if (payload == null) throw new InvalidReviewEventException("WorkCompleted payload가 비어 있습니다.");
			return payload;
		} catch (JsonProcessingException exception) {
			throw new InvalidReviewEventException("WorkCompleted payload를 해석할 수 없습니다.", exception);
		}
	}

	// Outbox의 DATETIME(6) 저장은 payload의 마이크로초 미만을 절삭하거나 반올림할 수 있다. 그 차이만 같은 발생 시각으로 본다.
	private static boolean isSameOccurrence(LocalDateTime envelopeTime, LocalDateTime payloadTime) {
		if (payloadTime == null) return false;
		if (envelopeTime.equals(payloadTime)) return true;
		LocalDateTime truncated = payloadTime.truncatedTo(ChronoUnit.MICROS);
		boolean hasSubMicros = !payloadTime.equals(truncated);
		return hasSubMicros && (envelopeTime.equals(truncated) || envelopeTime.equals(truncated.plusNanos(1_000)));
	}

	private static boolean isPositive(Long value) {
		return value != null && value > 0;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Payload(String eventId, String eventType, Long aggregateId, Long revision, Integer version,
		LocalDateTime occurredAt, Long workId, Long jobPostId, Long ownerMemberId, Long workerMemberId,
		String status, LocalDate workDate) {
	}
}
