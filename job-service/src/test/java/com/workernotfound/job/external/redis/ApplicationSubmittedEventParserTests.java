package com.workernotfound.job.external.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.job.domain.job.event.ApplicationSubmittedEvent;
import com.workernotfound.job.domain.job.exception.InvalidAdmissionEventException;
import com.workernotfound.job.support.MatchingApplicationEventFixture;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// job-service의 ObjectMapper 빈과 같은 설정으로 matching-service가 실제로 발행하는 레코드 형식을 해석한다.
class ApplicationSubmittedEventParserTests {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 10, 17, 10, 0, 0, 123_456_789);

    private final ApplicationSubmittedEventParser parser =
            new ApplicationSubmittedEventParser(new ObjectMapper().findAndRegisterModules());

    @Test
    void parsesRecordInMatchingPublishedFormat() {
        Map<String, String> fields = MatchingApplicationEventFixture.submitted(10L, 20L, 30L, 40L, OCCURRED_AT);

        ApplicationSubmittedEvent event = parser.parse(asRecord(fields));

        assertThat(fields.get("payload")).contains("\"occurredAt\":[2026,10,17,10,0,0,123456789]");
        assertThat(event).isEqualTo(new ApplicationSubmittedEvent(fields.get("eventId"), 10L, 1L, 20L, 30L, 40L,
                LocalDateTime.of(2026, 10, 17, 10, 0, 0, 123_457_000)));
    }

    // envelope는 Outbox DATETIME(6)에 저장된 값이라 payload의 나노초를 반올림하거나 절삭한다. 둘 다 같은 발생 시각이다.
    @Test
    void acceptsEnvelopeTimeRoundedOrTruncatedFromPayload() {
        Map<String, String> fields = MatchingApplicationEventFixture.submitted(10L, 20L, 30L, 40L, OCCURRED_AT);
        fields.put("occurredAt", "2026-10-17T10:00:00.123456");

        assertThat(parser.parse(asRecord(fields)).occurredAt())
                .isEqualTo(LocalDateTime.of(2026, 10, 17, 10, 0, 0, 123_456_000));
    }

    @Test
    void parsesWholeMinuteTimesWrittenWithoutSeconds() {
        LocalDateTime wholeMinute = LocalDateTime.of(2026, 10, 17, 10, 0);
        Map<String, String> fields = MatchingApplicationEventFixture.submitted(10L, 20L, 30L, 40L, wholeMinute);

        assertThat(fields.get("occurredAt")).isEqualTo("2026-10-17T10:00");
        assertThat(parser.parse(asRecord(fields)).occurredAt()).isEqualTo(wholeMinute);
    }

    // 업무 값의 누락은 처리 결과의 영구 거절로 기록되도록 그대로 넘긴다.
    @Test
    void passesMissingBusinessValuesThrough() {
        Map<String, String> fields = MatchingApplicationEventFixture.submitted(10L, null, null, null, OCCURRED_AT);

        ApplicationSubmittedEvent event = parser.parse(asRecord(fields));

        assertThat(event.admissionId()).isNull();
        assertThat(event.jobPostId()).isNull();
        assertThat(event.workerMemberId()).isNull();
    }

    @Test
    void passesMissingOccurredAtThroughWhenEnvelopeAndPayloadBothLackIt() {
        Map<String, String> fields = MatchingApplicationEventFixture.submitted(10L, 20L, 30L, 40L, OCCURRED_AT);
        fields.remove("occurredAt");
        fields.put("payload", fields.get("payload").replace("[2026,10,17,10,0,0,123456789]", "null"));

        assertThat(parser.parse(asRecord(fields)).occurredAt()).isNull();
    }

    @Test
    void identifiesOnlyApplicationSubmittedOfApplicationAggregate() {
        Map<String, String> fields = MatchingApplicationEventFixture.submitted(10L, 20L, 30L, 40L, OCCURRED_AT);
        assertThat(parser.isTarget(asRecord(fields))).isTrue();

        assertThat(parser.isTarget(asRecord(with(fields, f -> f.put("eventType", "ApplicationCanceled"))))).isFalse();
        assertThat(parser.isTarget(asRecord(with(fields, f -> f.put("aggregateType", "MATCHING"))))).isFalse();
        assertThat(parser.isTarget(asRecord(with(fields, f -> f.remove("eventType"))))).isFalse();
        assertThat(parser.isTarget(asRecord(with(fields, f -> f.remove("aggregateType"))))).isFalse();
    }

    @Test
    void refusesUninterpretableEnvelope() {
        Map<String, String> fields = MatchingApplicationEventFixture.submitted(10L, 20L, 30L, 40L, OCCURRED_AT);

        assertInvalid(with(fields, f -> f.remove("eventId")));
        assertInvalid(with(fields, f -> f.put("eventId", "e".repeat(129))));
        assertInvalid(with(fields, f -> f.put("eventId", "공백 포함")));
        assertInvalid(with(fields, f -> f.put("aggregateId", "abc")));
        assertInvalid(with(fields, f -> f.put("aggregateId", "0")));
        assertInvalid(with(fields, f -> f.remove("revision")));
        assertInvalid(with(fields, f -> f.put("version", "2")));
        assertInvalid(with(fields, f -> f.put("occurredAt", "2026/10/17 10:00")));
        assertInvalid(with(fields, f -> f.remove("payload")));
        assertInvalid(with(fields, f -> f.put("payload", "{not json")));
        assertInvalid(with(fields, f -> f.put("payload", "null")));
    }

    @Test
    void refusesEnvelopeThatDisagreesWithPayload() {
        Map<String, String> fields = MatchingApplicationEventFixture.submitted(10L, 20L, 30L, 40L, OCCURRED_AT);

        assertInvalid(with(fields, f -> f.put("eventId", "other-event")));
        assertInvalid(with(fields, f -> f.put("aggregateId", "11")));
        assertInvalid(with(fields, f -> f.put("revision", "2")));
        assertInvalid(with(fields, f -> f.put("occurredAt", "2026-10-17T10:00:00.123458")));
        assertInvalid(with(fields, f -> f.remove("occurredAt")));
        assertInvalid(with(fields, f -> f.put("payload", f.get("payload")
                .replace("\"eventType\":\"ApplicationSubmitted\"", "\"eventType\":\"ApplicationCanceled\""))));
        assertInvalid(with(fields, f -> f.put("payload", f.get("payload")
                .replace("\"applicationId\":10", "\"applicationId\":99"))));
    }

    private void assertInvalid(Map<String, String> fields) {
        assertThatThrownBy(() -> parser.parse(asRecord(fields))).isInstanceOf(InvalidAdmissionEventException.class);
    }

    private static Map<String, String> with(Map<String, String> fields, Consumer<Map<String, String>> change) {
        Map<String, String> changed = new HashMap<>(fields);
        change.accept(changed);
        return changed;
    }

    // StringRedisTemplate의 Stream 레코드 값과 같은 형태다.
    private static Map<Object, Object> asRecord(Map<String, String> fields) {
        return new HashMap<>(fields);
    }
}
