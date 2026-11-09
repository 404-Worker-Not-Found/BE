package com.workernotfound.member.external.redis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.member.domain.review.service.WorkNoShow;
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
 * work-service가 {@code work:domain-events}에 발행한 WorkNoShow 레코드를 {@link WorkNoShow}로 해석한다.
 *
 * <p>근무별 revision은 출근 1, 시작 2, 완료 3, 노쇼 4, 동의 취소 5로 고정이므로 WorkNoShow는 revision 4, 상태
 * {@code NO_SHOW}여야 한다. 노쇼는 출근 마감 이후 시스템만 판정하므로 처리자는 {@code SYSTEM}이고 처리 회원 ID가 없어야 한다.
 * envelope와 payload가 맞지 않거나 참여자·공고 ID가 비어 있거나 같으면 {@link InvalidWorkEventException}을 던진다.
 */
@Component
@RequiredArgsConstructor
public class WorkNoShowEventParser {
	static final String EVENT_TYPE = "WorkNoShow";
	static final String AGGREGATE_TYPE = "WORK";
	private static final long NO_SHOW_REVISION = 4;
	private static final String NO_SHOW_STATUS = "NO_SHOW";
	private static final String SYSTEM_ACTOR = "SYSTEM";
	private static final String SUPPORTED_VERSION = "1";
	// 수신 기록 컬럼(VARCHAR(128) ascii)에 저장할 수 있는 값만 받는다.
	private static final Pattern EVENT_ID_PATTERN = Pattern.compile("[!-~]{1,128}");

	private final ObjectMapper mapper;

	public boolean isTarget(Map<Object, Object> fields) {
		return EVENT_TYPE.equals(fields.get("eventType")) && AGGREGATE_TYPE.equals(fields.get("aggregateType"));
	}

	public WorkNoShow parse(Map<Object, Object> fields) {
		String eventId = requireEventId(fields.get("eventId"));
		long aggregateId = requirePositiveLong(fields.get("aggregateId"), "aggregateId");
		long revision = requirePositiveLong(fields.get("revision"), "revision");
		if (!SUPPORTED_VERSION.equals(fields.get("version")))
			throw new InvalidWorkEventException("지원하지 않는 WorkNoShow 스키마 버전입니다.");
		LocalDateTime occurredAt = parseOccurredAt(fields.get("occurredAt"));
		Payload payload = readPayload(fields.get("payload"));
		if (!payload.hasSameEnvelope(eventId, aggregateId, revision) || !isSameOccurrence(occurredAt, payload.occurredAt()))
			throw new InvalidWorkEventException("WorkNoShow envelope와 payload가 일치하지 않습니다.");
		if (revision != NO_SHOW_REVISION || !payload.isSystemNoShow())
			throw new InvalidWorkEventException("WorkNoShow revision·상태·처리자가 계약과 다릅니다.");
		if (!payload.hasDistinctParticipants())
			throw new InvalidWorkEventException("WorkNoShow 공고·참여자 ID가 계약과 다릅니다.");
		return new WorkNoShow(eventId, aggregateId, payload.jobPostId(), payload.ownerMemberId(),
			payload.workerMemberId(), payload.workDate(), occurredAt.truncatedTo(ChronoUnit.MICROS));
	}

	private static String requireEventId(Object value) {
		if (value instanceof String eventId && EVENT_ID_PATTERN.matcher(eventId).matches()) return eventId;
		throw new InvalidWorkEventException("WorkNoShow eventId가 없거나 형식이 잘못되었습니다.");
	}

	private static long requirePositiveLong(Object value, String name) {
		try {
			long parsed = Long.parseLong(String.valueOf(value));
			if (parsed > 0) return parsed;
		} catch (NumberFormatException exception) {
			throw new InvalidWorkEventException("WorkNoShow " + name + "가 양의 정수가 아닙니다.", exception);
		}
		throw new InvalidWorkEventException("WorkNoShow " + name + "가 양의 정수가 아닙니다.");
	}

	private static LocalDateTime parseOccurredAt(Object value) {
		try {
			return LocalDateTime.parse(String.valueOf(value));
		} catch (DateTimeParseException exception) {
			throw new InvalidWorkEventException("WorkNoShow occurredAt 형식이 잘못되었습니다.", exception);
		}
	}

	// Jackson 오류 메시지에는 payload 일부가 담길 수 있어 원인 예외로만 보존한다.
	private Payload readPayload(Object value) {
		if (!(value instanceof String json)) throw new InvalidWorkEventException("WorkNoShow payload가 없습니다.");
		try {
			Payload payload = mapper.readValue(json, Payload.class);
			if (payload == null) throw new InvalidWorkEventException("WorkNoShow payload가 비어 있습니다.");
			return payload;
		} catch (JsonProcessingException exception) {
			throw new InvalidWorkEventException("WorkNoShow payload를 해석할 수 없습니다.", exception);
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

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Payload(String eventId, String eventType, Long aggregateId, Long revision, Integer version,
		LocalDateTime occurredAt, Long workId, Long jobPostId, Long ownerMemberId, Long workerMemberId,
		Long actorMemberId, String actorRole, String status, LocalDate workDate) {

		boolean hasSameEnvelope(String envelopeEventId, long envelopeAggregateId, long envelopeRevision) {
			return envelopeEventId.equals(eventId) && EVENT_TYPE.equals(eventType)
				&& Objects.equals(envelopeAggregateId, aggregateId) && Objects.equals(envelopeAggregateId, workId)
				&& Objects.equals(envelopeRevision, revision) && Objects.equals(1, version);
		}

		boolean isSystemNoShow() {
			return NO_SHOW_STATUS.equals(status) && SYSTEM_ACTOR.equals(actorRole) && actorMemberId == null;
		}

		boolean hasDistinctParticipants() {
			return isPositive(jobPostId) && isPositive(ownerMemberId) && isPositive(workerMemberId)
				&& !ownerMemberId.equals(workerMemberId);
		}

		private static boolean isPositive(Long value) {
			return value != null && value > 0;
		}
	}
}
