package com.workernotfound.notification;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.notification.domain.notification.dto.response.NotificationResponse;
import com.workernotfound.notification.domain.notification.event.NotificationEvent;
import com.workernotfound.notification.domain.notification.service.*;
import com.workernotfound.notification.external.redis.NotificationEventConsumer;
import com.workernotfound.notification.global.security.AuthenticatedMember;
import com.workernotfound.notification.support.IntegrationTestSupport;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class NotificationIntegrationTests extends IntegrationTestSupport {
  static final AtomicLong IDS = new AtomicLong(1000);
  static final String STREAM = "matching:domain-events";
  @Autowired NotificationEventService events;
  @Autowired NotificationService notifications;
  @Autowired NotificationEventConsumer consumer;
  @Autowired StringRedisTemplate redis;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired PlatformTransactionManager transactions;

  NotificationEvent confirmed() {
    long id = IDS.addAndGet(10);
    return new NotificationEvent(UUID.randomUUID().toString(), "MatchConfirmed", LocalDateTime.now(),
        id, 1L, 1, id, id + 1, id + 2, id + 3, id + 4, null);
  }

  NotificationEvent rejected(NotificationEvent original, String eventId) {
    return new NotificationEvent(eventId, "ApplicationRejected", original.occurredAt(),
        original.applicationId(), 2L, 1, null, original.applicationId(), original.jobPostId(),
        null, original.workerMemberId(), "REJECTED");
  }

  AuthenticatedMember owner(NotificationEvent event) {
    return new AuthenticatedMember(event.ownerMemberId(), event.ownerMemberId(), "OWNER");
  }

  AuthenticatedMember worker(NotificationEvent event) {
    return new AuthenticatedMember(event.workerMemberId(), event.workerMemberId(), "WORKER");
  }

  Map<String, String> fields(NotificationEvent event) throws Exception {
    return Map.of("eventId", event.eventId(), "eventType", event.eventType(),
        "aggregateType", event.eventType().equals("MatchConfirmed") ? "MATCHING" : "APPLICATION",
        "aggregateId", event.aggregateId().toString(), "revision", event.revision().toString(),
        "version", event.version().toString(), "occurredAt", event.occurredAt().toString(),
        "payload", mapper.writeValueAsString(event));
  }

  String token(long memberId, String role) throws Exception {
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String header = encoder.encodeToString(
        "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String payload = mapper.writeValueAsString(Map.of("authAccountId", memberId,
        "memberId", memberId, "role", role,
        "exp", Instant.now().plusSeconds(300).getEpochSecond()));
    String unsigned = header + "." + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(
        "notification-test-jwt-secret-for-integration-only".getBytes(StandardCharsets.UTF_8),
        "HmacSHA256"));
    return "Bearer " + unsigned + "." + encoder.encodeToString(
        mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
  }


  @Test
  void confirmationCreatesTwoPrivateNoticesAndReadIsIdempotent() throws Exception {
    var event = confirmed();
    events.receive(event);
    events.receive(event);
    for (var member : List.of(owner(event), worker(event))) {
      var page = notifications.getNotifications(member, null, 20);
      assertThat(page.content()).hasSize(1);
      var notice = page.content().get(0);
      assertThat(notice.type()).isEqualTo("MatchConfirmed");
      assertThat(notice.jobPostId()).isEqualTo(event.jobPostId());
      assertThat(notifications.getUnreadCount(member).unreadCount()).isEqualTo(1);
      String auth = token(member.memberId(), member.role());
      mvc.perform(get("/api/notifications/me").header("Authorization", auth))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].notificationId").value(notice.notificationId()))
          .andExpect(jsonPath("$.data.content[0].memberId").doesNotExist())
          .andExpect(jsonPath("$.data.content[0].eventId").doesNotExist());
      mvc.perform(patch("/api/notifications/me/" + notice.notificationId() + "/read")
          .header("Authorization", token(999999, "WORKER"))).andExpect(status().isNotFound());
      mvc.perform(patch("/api/notifications/me/" + notice.notificationId() + "/read")
          .header("Authorization", token(member.memberId(), member.role().equals("OWNER") ? "WORKER" : "OWNER")))
          .andExpect(status().isNotFound());
      var read = notifications.markRead(member, notice.notificationId());
      assertThat(read.readAt()).isNotNull();
      assertThat(notifications.markRead(member, notice.notificationId()).readAt()).isEqualTo(read.readAt());
      mvc.perform(patch("/api/notifications/me/" + notice.notificationId() + "/read")
          .header("Authorization", auth)).andExpect(status().isOk());
      mvc.perform(get("/api/notifications/me/unread-count").header("Authorization", auth))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data.unreadCount").value(0));
    }
  }

  @Test
  void rejectionOnlyNotifiesWorkerAndPaginationIsBounded() throws Exception {
    var source = confirmed();
    for (int i = 0; i < 3; i++) events.receive(rejected(source, UUID.randomUUID().toString()));
    assertThat(notifications.getNotifications(owner(source), null, 20).content()).isEmpty();
    var first = notifications.getNotifications(worker(source), null, 2);
    assertThat(first.content()).hasSize(2);
    assertThat(first.hasNext()).isTrue();
    var last = notifications.getNotifications(worker(source), first.nextBeforeId(), 2);
    assertThat(last.content()).hasSize(1);
    assertThat(last.content().get(0).matchingId()).isNull();
    assertThat(last.hasNext()).isFalse();
    assertThat(last.nextBeforeId()).isNull();
    mvc.perform(get("/api/notifications/me").header("Authorization", token(source.workerMemberId(), "WORKER"))
        .param("size", "1")).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[1]").doesNotExist())
        .andExpect(jsonPath("$.data.hasNext").value(true));
  }

  @Test
  void conflictingAndInvalidEventsCannotChangeRecipientsAndRollbackIsAtomic() {
    var event = confirmed();
    events.receive(event);
    var changed = new NotificationEvent(event.eventId(), event.eventType(), event.occurredAt(), event.aggregateId(),
        event.revision(), event.version(), event.matchingId(), event.applicationId(), event.jobPostId(),
        event.ownerMemberId(), event.workerMemberId() + 100, null);
    assertThatThrownBy(() -> events.receive(changed)).isInstanceOf(IllegalArgumentException.class);
    assertThat(notifications.getUnreadCount(worker(event)).unreadCount()).isEqualTo(1);
    assertThat(notifications.getUnreadCount(worker(changed)).unreadCount()).isZero();
    var badVersion = new NotificationEvent(UUID.randomUUID().toString(), event.eventType(), event.occurredAt(),
        event.aggregateId(), 1L, 2, event.matchingId(), event.applicationId(), event.jobPostId(),
        event.ownerMemberId(), event.workerMemberId(), null);
    assertThatThrownBy(() -> events.receive(badVersion)).isInstanceOf(IllegalArgumentException.class);
    var badOwner = new NotificationEvent(UUID.randomUUID().toString(), event.eventType(), event.occurredAt(),
        event.aggregateId(), 1L, 1, event.matchingId(), event.applicationId(), event.jobPostId(), null,
        event.workerMemberId(), null);
    assertThatThrownBy(() -> events.receive(badOwner)).isInstanceOf(IllegalArgumentException.class);
    var rolledBack = confirmed();
    new TransactionTemplate(transactions).executeWithoutResult(tx -> {
      events.receive(rolledBack);
      tx.setRollbackOnly();
    });
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_events WHERE event_id = ?", Long.class,
        rolledBack.eventId())).isZero();
    assertThat(notifications.getUnreadCount(worker(rolledBack)).unreadCount()).isZero();
    events.receive(rolledBack);
    assertThat(notifications.getUnreadCount(worker(rolledBack)).unreadCount()).isEqualTo(1);
  }

  @Test
  void simultaneousRedeliveryAndReadsRemainIdempotent() throws Exception {
    var event = confirmed();
    var executor = Executors.newFixedThreadPool(6);
    var start = new CountDownLatch(1);
    try {
      var futures = new ArrayList<Future<?>>();
      for (int i = 0; i < 6; i++) futures.add(executor.submit(() -> {
        start.await(); events.receive(event); return null;
      }));
      start.countDown();
      for (var future : futures) future.get(15, TimeUnit.SECONDS);
      assertThat(notifications.getUnreadCount(worker(event)).unreadCount()).isEqualTo(1);
      assertThat(notifications.getUnreadCount(owner(event)).unreadCount()).isEqualTo(1);
      Long id = notifications.getNotifications(worker(event), null, 20).content().get(0).notificationId();
      var reads = new ArrayList<Future<LocalDateTime>>();
      for (int i = 0; i < 6; i++) reads.add(executor.submit(() -> notifications.markRead(worker(event), id).readAt()));
      var timestamps = new HashSet<LocalDateTime>();
      for (var read : reads) timestamps.add(read.get(15, TimeUnit.SECONDS));
      assertThat(timestamps).hasSize(1);
    } finally { executor.shutdownNow(); }
  }

  @Test
  void streamRecoversPendingAndRetainsInvalidEnvelopeWhileProcessingNewEvents() throws Exception {
    var first = confirmed();
    redis.opsForStream().add(STREAM, fields(first));
    consumer.poll();
    assertThat(notifications.getUnreadCount(worker(first)).unreadCount()).isEqualTo(1);
    var pending = confirmed();
    var pendingId = redis.opsForStream().add(STREAM, fields(pending));
    redis.opsForStream().read(Consumer.from("notification-results-v1", "notification-results"),
        StreamReadOptions.empty(), StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
    consumer.poll(); consumer.poll();
    assertThat(notifications.getUnreadCount(worker(pending)).unreadCount()).isEqualTo(1);
    assertThat(redis.opsForStream().pending(STREAM, "notification-results-v1",
        org.springframework.data.domain.Range.closed(pendingId.getValue(), pendingId.getValue()), 10)).isEmpty();
    var invalid = new HashMap<>(fields(first));
    invalid.put("aggregateType", "APPLICATION");
    var invalidId = redis.opsForStream().add(STREAM, invalid);
    var next = confirmed();
    redis.opsForStream().add(STREAM, fields(next));
    var extra = new HashMap<>(fields(first));
    var payload = mapper.readTree(extra.get("payload"));
    ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("futureField", "ignored");
    extra.put("payload", mapper.writeValueAsString(payload));
    redis.opsForStream().add(STREAM, extra);
    consumer.poll();
    assertThat(notifications.getUnreadCount(worker(next)).unreadCount()).isEqualTo(1);
    assertThat(notifications.getUnreadCount(worker(first)).unreadCount()).isEqualTo(1);
    assertThat(redis.opsForStream().pending(STREAM, "notification-results-v1",
        org.springframework.data.domain.Range.closed(invalidId.getValue(), invalidId.getValue()), 10)).hasSize(1);
    var rejected = rejected(next, UUID.randomUUID().toString());
    redis.opsForStream().add(STREAM, fields(rejected));
    consumer.poll();
    assertThat(notifications.getUnreadCount(worker(next)).unreadCount()).isEqualTo(2);
    assertThat(notifications.getUnreadCount(owner(next)).unreadCount()).isEqualTo(1);
  }

  @Test
  void mismatchedEnvelopeTimeRemainsPendingWithoutCreatingANotice() throws Exception {
    var event = confirmed();
    var invalid = new HashMap<>(fields(event));
    invalid.put("occurredAt", event.occurredAt().minusDays(1).toString());
    var recordId = redis.opsForStream().add(STREAM, invalid);
    consumer.poll();
    assertThat(notifications.getUnreadCount(worker(event)).unreadCount()).isZero();
    assertThat(redis.opsForStream().pending(STREAM, "notification-results-v1",
        org.springframework.data.domain.Range.closed(recordId.getValue(), recordId.getValue()), 10)).hasSize(1);
    redis.opsForStream().add(STREAM, fields(event));
    consumer.poll();
    assertThat(notifications.getUnreadCount(worker(event)).unreadCount()).isEqualTo(1);
  }

  @Test
  void databaseMicrosecondEnvelopeStillAcceptsNanosecondPayload() throws Exception {
    for (int nanos : List.of(123456100, 123456789, 999999789)) {
      var original = confirmed();
      var at = original.occurredAt().withNano(nanos);
      var event = new NotificationEvent(original.eventId(), original.eventType(), at,
          original.aggregateId(), original.revision(), original.version(), original.matchingId(),
          original.applicationId(), original.jobPostId(), original.ownerMemberId(), original.workerMemberId(), null);
      LocalDateTime stored = jdbc.queryForObject("SELECT CAST(? AS DATETIME(6))", LocalDateTime.class, at);
      assertThat(stored).isNotEqualTo(at);
      var micros = at.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
      for (var persisted : List.of(stored, micros, micros.plusNanos(1000))) {
        var envelope = new HashMap<>(fields(event));
        envelope.put("occurredAt", persisted.toString());
        var id = redis.opsForStream().add(STREAM, envelope);
        consumer.poll();
        assertThat(notifications.getUnreadCount(worker(event)).unreadCount()).isEqualTo(1);
        assertThat(redis.opsForStream().pending(STREAM, "notification-results-v1",
            org.springframework.data.domain.Range.closed(id.getValue(), id.getValue()), 10)).isEmpty();
      }
    }
  }

  @Test
  void timestampToleranceDoesNotAcceptAnotherMicrosecondOrNonDatabasePrecision() throws Exception {
    var original = confirmed();
    var at = original.occurredAt().withNano(123456000);
    var event = new NotificationEvent(original.eventId(), original.eventType(), at,
        original.aggregateId(), original.revision(), original.version(), original.matchingId(),
        original.applicationId(), original.jobPostId(), original.ownerMemberId(), original.workerMemberId(), null);
    for (var mismatched : List.of(at.plusNanos(1000), at.minusNanos(1000), at.plusNanos(1))) {
      var envelope = new HashMap<>(fields(event));
      envelope.put("occurredAt", mismatched.toString());
      var id = redis.opsForStream().add(STREAM, envelope);
      consumer.poll();
      assertThat(notifications.getUnreadCount(worker(event)).unreadCount()).isZero();
      assertThat(redis.opsForStream().pending(STREAM, "notification-results-v1",
          org.springframework.data.domain.Range.closed(id.getValue(), id.getValue()), 10)).hasSize(1);
    }
  }

  @Test
  void validationAuthenticationAndOpenApi() throws Exception {
    String auth = token(1, "OWNER");
    mvc.perform(get("/api/notifications/me")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/notifications/me").header("Authorization", "Bearer invalid"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/notifications/me").header("Authorization", token(1, "ADMIN")))
        .andExpect(status().isUnauthorized());
    for (String size : List.of("0", "101", "-1"))
      mvc.perform(get("/api/notifications/me").header("Authorization", auth).param("size", size))
          .andExpect(status().isBadRequest());
    mvc.perform(get("/api/notifications/me").header("Authorization", auth).param("beforeId", "0"))
        .andExpect(status().isBadRequest());
    mvc.perform(patch("/api/notifications/me/0/read").header("Authorization", auth))
        .andExpect(status().isBadRequest());
    mvc.perform(patch("/api/notifications/me/999999/read").header("Authorization", auth))
        .andExpect(status().isNotFound());
    mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/notifications/me'].get").exists());
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
  }
}
