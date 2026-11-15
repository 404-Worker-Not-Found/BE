package com.workernotfound.matching.external.redis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.matching.domain.score.model.TrustScoreUpdate;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// member-service TrustScoreUpdated v1. envelope와 payload의 식별 값이 같고 점수·리뷰 수가 계약 범위여야 한다.
// 리뷰 없이 노쇼만 있는 회원은 reviewCount가 0이다. 점수 계산에 쓰지 않는 noShowCount는 읽지 않는다.
@Component
@RequiredArgsConstructor
public class TrustScoreEventParser {
	static final String EVENT_TYPE = "TrustScoreUpdated";
	static final String AGGREGATE_TYPE = "MEMBER";
	private static final int SCHEMA_VERSION = 1;
	private static final BigDecimal MAX_SCORE = BigDecimal.valueOf(100);

	private final ObjectMapper mapper;

	public boolean isTarget(Map<Object, Object> fields) {
		return EVENT_TYPE.equals(fields.get("eventType")) && AGGREGATE_TYPE.equals(fields.get("aggregateType"));
	}

	public TrustScoreUpdate parse(Map<Object, Object> fields) {
		if (!(fields.get("payload") instanceof String payload))
			throw new IllegalArgumentException("신뢰 점수 이벤트 본문이 없습니다.");
		Payload event = read(payload);
		if (event == null || !event.isValid() || !hasSameEnvelope(event, fields))
			throw new IllegalArgumentException("신뢰 점수 이벤트 계약이 일치하지 않습니다.");
		return new TrustScoreUpdate(event.eventId(), event.memberId(), event.revision(), event.trustScore(),
			event.reviewCount(), event.policyVersion());
	}

	private Payload read(String payload) {
		try {
			return mapper.readValue(payload, Payload.class);
		} catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("신뢰 점수 이벤트를 해석할 수 없습니다.");
		}
	}

	private boolean hasSameEnvelope(Payload event, Map<Object, Object> fields) {
		return Objects.equals(event.eventId(), fields.get("eventId"))
			&& Objects.equals(event.eventType(), fields.get("eventType"))
			&& Objects.equals(String.valueOf(event.memberId()), fields.get("aggregateId"))
			&& Objects.equals(String.valueOf(event.revision()), fields.get("revision"))
			&& Objects.equals(String.valueOf(event.version()), fields.get("version"));
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Payload(String eventId, String eventType, Long memberId, Long revision, Integer version,
		BigDecimal trustScore, Long reviewCount, String policyVersion) {

		boolean isValid() {
			return hasText(eventId) && eventId.length() <= 128 && EVENT_TYPE.equals(eventType)
				&& isPositive(memberId) && isPositive(revision) && Objects.equals(version, SCHEMA_VERSION)
				&& trustScore != null && trustScore.signum() >= 0 && trustScore.compareTo(MAX_SCORE) <= 0
				&& trustScore.stripTrailingZeros().scale() <= 2
				&& reviewCount != null && reviewCount >= 0 && reviewCount <= Integer.MAX_VALUE
				&& hasText(policyVersion) && policyVersion.length() <= 50;
		}

		private static boolean isPositive(Long value) {
			return value != null && value > 0;
		}

		private static boolean hasText(String value) {
			return value != null && !value.isBlank();
		}
	}
}
