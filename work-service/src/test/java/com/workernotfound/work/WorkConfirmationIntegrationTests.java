package com.workernotfound.work;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.work.domain.work.dto.request.ScheduledWorkRequest;
import com.workernotfound.work.domain.work.event.MatchConfirmedEvent;
import com.workernotfound.work.domain.work.service.*;
import com.workernotfound.work.external.redis.WorkConfirmationConsumer;
import com.workernotfound.work.global.exception.BusinessException;
import com.workernotfound.work.support.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class WorkConfirmationIntegrationTests extends IntegrationTestSupport {
  static final AtomicLong IDS = new AtomicLong(10000);
  static final String STREAM = "matching:domain-events";
  @Autowired WorkApplicationService works;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  WorkConfirmationService confirmations;

  @Autowired WorkConfirmationConsumer consumer;
  @Autowired StringRedisTemplate redis;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;

  ScheduledWorkRequest request(long id) {
    return new ScheduledWorkRequest(
        id,
        id,
        id + 1,
        id + 2,
        "payment-" + id,
        LocalDate.of(2026, 9, 23),
        LocalTime.of(23, 0),
        LocalTime.of(2, 0));
  }

  MatchConfirmedEvent event(ScheduledWorkRequest request, String workId, long revision) {
    return new MatchConfirmedEvent(
        UUID.randomUUID().toString(),
        "MatchConfirmed",
        LocalDateTime.of(2026, 9, 22, 12, 0),
        request.matchingId(),
        revision,
        1,
        request.matchingId(),
        workId,
        request.jobPostId(),
        request.ownerMemberId(),
        request.workerMemberId(),
        request.paymentId(),
        request.workDate(),
        request.startTime(),
        request.endTime());
  }

  Map<String, String> fields(MatchConfirmedEvent event) throws Exception {
    return Map.of(
        "eventId",
        event.eventId(),
        "eventType",
        event.eventType(),
        "aggregateType",
        "MATCHING",
        "aggregateId",
        event.aggregateId().toString(),
        "revision",
        event.revision().toString(),
        "version",
        "1",
        "payload",
        mapper.writeValueAsString(event));
  }

  String create(ScheduledWorkRequest request) {
    return works.create(UUID.randomUUID().toString(), request).workId();
  }

  String token(long memberId, String role) throws Exception {
    return token(memberId, role, Instant.now().plusSeconds(300).getEpochSecond());
  }

  String token(long memberId, String role, long expiry) throws Exception {
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String header =
        encoder.encodeToString(
            "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String payload =
        mapper.writeValueAsString(
            Map.of("authAccountId", memberId, "memberId", memberId, "role", role, "exp", expiry));
    String unsigned =
        header + "." + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(
        new SecretKeySpec(
            "work-test-jwt-secret-for-integration-only".getBytes(StandardCharsets.UTF_8),
            "HmacSHA256"));
    return "Bearer "
        + unsigned
        + "."
        + encoder.encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void onlyConfirmedParticipantWorksAreVisibleWithJwtAndPagination() throws Exception {
    var request = request(IDS.incrementAndGet());
    String id = create(request);
    String owner = token(request.ownerMemberId(), "OWNER");
    String worker = token(request.workerMemberId(), "WORKER");
    mvc.perform(get("/api/works/me/" + id).header("Authorization", owner))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/works/me").header("Authorization", worker))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(0));
    confirmations.confirm(event(request, id, 2));
    for (String authorization : List.of(owner, worker)) {
      mvc.perform(get("/api/works/me/" + id).header("Authorization", authorization))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.workId").value(Long.valueOf(id)))
          .andExpect(jsonPath("$.data.status").value("SCHEDULED"))
          .andExpect(jsonPath("$.data.paymentId").doesNotExist());
      mvc.perform(get("/api/works/me").param("size", "1").header("Authorization", authorization))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.totalElements").value(1))
          .andExpect(jsonPath("$.data.content[0].workId").value(Long.valueOf(id)));
      mvc.perform(get("/api/works/me").param("page", "1").header("Authorization", authorization))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content").isEmpty());
    }
    for (String authorization :
        List.of(token(999999, "WORKER"), token(request.ownerMemberId(), "WORKER"))) {
      mvc.perform(get("/api/works/me/" + id).header("Authorization", authorization))
          .andExpect(status().isNotFound());
    }
    assertThatThrownBy(() -> works.cancel(UUID.randomUUID().toString(), Long.valueOf(id)))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void securityValidationAndSwaggerRemainAvailable() throws Exception {
    mvc.perform(get("/api/works/me")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/works/me").header("Authorization", "Bearer invalid"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/works/me").header("Authorization", token(1, "OWNER", 1)))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/works/me").header("Authorization", token(1, "ADMIN")))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/works/me").param("size", "101").header("Authorization", token(1, "OWNER")))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/works/me").param("page", "-1").header("Authorization", token(1, "OWNER")))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/works/me/0").header("Authorization", token(1, "OWNER")))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    mvc.perform(
            post("/api/works/internal/1/cancel")
                .header("Idempotency-Key", "test")
                .header("Authorization", token(1, "OWNER")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void streamReplaysHistoricalAndPendingDeliveriesWithoutDuplicateEffects() throws Exception {
    var request = request(IDS.incrementAndGet());
    String id = create(request);
    var event = event(request, id, 3);
    var record = redis.opsForStream().add(STREAM, fields(event));
    consumer.poll();
    // Simulate process death after delivery but before handling/acknowledgment.
    var pendingEvent = event(request, id, 4);
    var pendingRecord = redis.opsForStream().add(STREAM, fields(pendingEvent));
    redis
        .opsForStream()
        .read(
            Consumer.from("work-confirmation-v1", "work-confirmation"),
            StreamReadOptions.empty(),
            StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
    consumer.poll();
    redis.opsForStream().add(STREAM, fields(event));
    consumer.poll();
    assertThat(
            jdbc.queryForObject(
                "SELECT confirmation_revision FROM works WHERE id = ?", Long.class, id))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_confirmation_events WHERE event_id = ?",
                Long.class,
                event.eventId()))
        .isEqualTo(1);
    assertThat(
            redis
                .opsForStream()
                .pending(
                    STREAM,
                    "work-confirmation-v1",
                    org.springframework.data.domain.Range.closed(
                        record.getValue(), record.getValue()),
                    10))
        .isEmpty();
    assertThat(
            redis
                .opsForStream()
                .pending(
                    STREAM,
                    "work-confirmation-v1",
                    org.springframework.data.domain.Range.closed(
                        pendingRecord.getValue(), pendingRecord.getValue()),
                    10))
        .isEmpty();
  }

  @Test
  void poisonRecordDoesNotBlockValidEvents() throws Exception {
    var request = request(IDS.incrementAndGet());
    String id = create(request);
    var malformed = new HashMap<>(fields(event(request, id, 2)));
    malformed.put("revision", "900");
    var bad = redis.opsForStream().add(STREAM, malformed);
    var valid = event(request, id, 2);
    redis.opsForStream().add(STREAM, fields(valid));
    consumer.poll();
    assertThat(
            jdbc.queryForObject(
                "SELECT confirmed_at IS NOT NULL FROM works WHERE id = ?", Boolean.class, id))
        .isTrue();
    assertThat(
            redis
                .opsForStream()
                .pending(
                    STREAM,
                    "work-confirmation-v1",
                    org.springframework.data.domain.Range.closed(bad.getValue(), bad.getValue()),
                    10))
        .hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_confirmation_events WHERE event_id = ?",
                Long.class,
                malformed.get("eventId")))
        .isZero();
    redis.opsForStream().acknowledge(STREAM, "work-confirmation-v1", bad);
  }

  @Test
  void concurrentDuplicatesAndCanceledOldAttemptsCannotAffectReplacement() throws Exception {
    var request = request(IDS.incrementAndGet());
    String oldId = create(request);
    works.cancel(UUID.randomUUID().toString(), Long.valueOf(oldId));
    String replacement = create(request);
    confirmations.confirm(event(request, oldId, 1));
    assertThat(
            jdbc.queryForObject(
                "SELECT confirmed_at IS NULL FROM works WHERE id = ?", Boolean.class, replacement))
        .isTrue();
    var event = event(request, replacement, 2);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      var tasks = new ArrayList<Future<?>>();
      for (int i = 0; i < 4; i++) tasks.add(pool.submit(() -> confirmations.confirm(event)));
      for (var task : tasks) task.get(10, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_confirmation_events WHERE event_id = ?",
                Long.class,
                event.eventId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT confirmation_revision FROM works WHERE id = ?", Long.class, replacement))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT confirmed_at IS NULL FROM works WHERE id = ?", Boolean.class, oldId))
        .isTrue();
  }

  @Test
  void transientDatabaseFailureLeavesPendingDeliveryForRecovery() throws Exception {
    var request = request(IDS.incrementAndGet());
    String id = create(request);
    var event = event(request, id, 2);
    var record = redis.opsForStream().add(STREAM, fields(event));
    org.mockito.Mockito.doThrow(
            new org.springframework.dao.TransientDataAccessResourceException("test"))
        .doCallRealMethod()
        .when(confirmations)
        .confirm(event);
    consumer.poll();
    assertThat(
            jdbc.queryForObject(
                "SELECT confirmed_at IS NULL FROM works WHERE id = ?", Boolean.class, id))
        .isTrue();
    assertThat(
            redis
                .opsForStream()
                .pending(
                    STREAM,
                    "work-confirmation-v1",
                    org.springframework.data.domain.Range.closed(
                        record.getValue(), record.getValue()),
                    10))
        .hasSize(1);
    consumer.poll();
    consumer.poll();
    assertThat(
            jdbc.queryForObject(
                "SELECT confirmed_at IS NOT NULL FROM works WHERE id = ?", Boolean.class, id))
        .isTrue();
    assertThat(
            redis
                .opsForStream()
                .pending(
                    STREAM,
                    "work-confirmation-v1",
                    org.springframework.data.domain.Range.closed(
                        record.getValue(), record.getValue()),
                    10))
        .isEmpty();
  }

  @Test
  void mismatchedSnapshotAndReusedEventIdRollBackWithoutChangingConfirmation() {
    var request = request(IDS.incrementAndGet());
    String id = create(request);
    var mismatch = request(IDS.incrementAndGet());
    var invalid = event(mismatch, id, 2);
    assertThatThrownBy(() -> confirmations.confirm(invalid))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_confirmation_events WHERE event_id = ?",
                Long.class,
                invalid.eventId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT confirmed_at IS NULL FROM works WHERE id = ?", Boolean.class, id))
        .isTrue();
    var original = event(request, id, 2);
    confirmations.confirm(original);
    var changed =
        new MatchConfirmedEvent(
            original.eventId(),
            original.eventType(),
            original.occurredAt(),
            original.aggregateId(),
            9L,
            original.version(),
            original.matchingId(),
            original.workId(),
            original.jobPostId(),
            original.ownerMemberId(),
            original.workerMemberId(),
            original.paymentId(),
            original.workDate(),
            original.startTime(),
            original.endTime());
    assertThatThrownBy(() -> confirmations.confirm(changed))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT confirmation_revision FROM works WHERE id = ?", Long.class, id))
        .isEqualTo(2);
  }
}
