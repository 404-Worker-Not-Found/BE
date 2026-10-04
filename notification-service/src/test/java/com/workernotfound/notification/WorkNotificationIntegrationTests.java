package com.workernotfound.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.notification.domain.notification.event.*;
import com.workernotfound.notification.domain.notification.service.*;
import com.workernotfound.notification.external.redis.WorkNotificationEventConsumer;
import com.workernotfound.notification.global.security.AuthenticatedMember;
import com.workernotfound.notification.support.IntegrationTestSupport;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class WorkNotificationIntegrationTests extends IntegrationTestSupport {
  static final AtomicLong IDS = new AtomicLong(400000);
  static final String STREAM = "work:domain-events";
  @Autowired WorkNotificationEventService events;
  @Autowired NotificationEventService matchingEvents;
  @Autowired NotificationService notifications;
  @Autowired WorkNotificationEventConsumer consumer;
  @Autowired StringRedisTemplate redis;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired PlatformTransactionManager transactions;

  WorkNotificationEvent event(String type, String actorRole) {
    long id = IDS.addAndGet(10);
    long revision = switch (type) { case "WorkCheckedIn" -> 1; case "WorkStarted" -> 2; default -> 3; };
    String status = switch (type) { case "WorkCheckedIn" -> "CHECKED_IN"; case "WorkStarted" -> "IN_PROGRESS"; default -> "COMPLETED"; };
    return new WorkNotificationEvent(UUID.randomUUID().toString(), type, id, revision, 1,
        LocalDateTime.of(2026, 10, 4, 18, 0), id, id+1, id+2, id+3, id+4,
        actorRole.equals("OWNER") ? id+3 : id+4, actorRole, status,
        LocalDate.of(2026, 10, 4), LocalTime.of(9, 0), LocalTime.of(18, 0), false);
  }

  AuthenticatedMember member(long id, String role) { return new AuthenticatedMember(id, id, role); }
  Map<String, String> fields(WorkNotificationEvent event) throws Exception {
    return Map.of("eventId", event.eventId(), "eventType", event.eventType(), "aggregateType", "WORK",
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
  void eachTransitionNotifiesOnlyTheOtherParticipantAndExposesWorkLink() throws Exception {
    for (var event : List.of(event("WorkCheckedIn", "WORKER"), event("WorkStarted", "OWNER"),
        event("WorkCompleted", "OWNER"), event("WorkCompleted", "WORKER"))) {
      events.receive(event); events.receive(event);
      boolean owner = event.actorRole().equals("WORKER");
      var recipient = member(owner ? event.ownerMemberId() : event.workerMemberId(), owner ? "OWNER" : "WORKER");
      var page = notifications.getNotifications(recipient, null, 20);
      assertThat(page.content()).hasSize(1);
      var notice = page.content().get(0);
      assertThat(notice.type()).isEqualTo(event.eventType());
      assertThat(notice.workId()).isEqualTo(event.workId());
      assertThat(notice.applicationId()).isNull();
      assertThat(notifications.getNotifications(member(event.actorMemberId(), event.actorRole()), null, 20).content()).isEmpty();
      String auth = token(recipient.memberId(), recipient.role());
      mvc.perform(get("/api/notifications/me").header("Authorization", auth)).andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].workId").value(event.workId()));
      mvc.perform(patch("/api/notifications/me/" + notice.notificationId() + "/read").header("Authorization", auth))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data.readAt").isNotEmpty());
      mvc.perform(patch("/api/notifications/me/" + notice.notificationId() + "/read")
          .header("Authorization", token(event.actorMemberId(), event.actorRole())))
          .andExpect(status().isNotFound());
    }
  }

  @Test
  void sameMemberIdStillRoutesUsingDeclaredActorRole() throws Exception {
    var original = event("WorkCompleted", "WORKER");
    var json = (com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(original);
    json.put("workerMemberId", original.ownerMemberId()).put("actorMemberId", original.ownerMemberId());
    events.receive(mapper.treeToValue(json, WorkNotificationEvent.class));
    assertThat(notifications.getNotifications(member(original.ownerMemberId(), "OWNER"), null, 20).content()).hasSize(1);
    assertThat(notifications.getNotifications(member(original.ownerMemberId(), "WORKER"), null, 20).content()).isEmpty();
  }

  @Test
  void malformedPayloadAndReusedEventIdCannotCreateNotifications() throws Exception {
    var original = event("WorkCompleted", "OWNER");
    events.receive(original);
    for (var change : Map.of("version", 2, "revision", 1, "aggregateId", 1,
        "actorRole", "ADMIN", "actorMemberId", 1, "status", "CHECKED_IN", "endTimeNextDay", true).entrySet()) {
      var json = mapper.valueToTree(original);
      ((com.fasterxml.jackson.databind.node.ObjectNode)json).set(change.getKey(), mapper.valueToTree(change.getValue()));
      var invalid = mapper.treeToValue(json, WorkNotificationEvent.class);
      assertThatThrownBy(() -> events.receive(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
    var changed = mapper.valueToTree(original);
    ((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("jobPostId", 999999L);
    assertThatThrownBy(() -> events.receive(mapper.treeToValue(changed, WorkNotificationEvent.class)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE event_id = ?", Long.class, original.eventId())).isEqualTo(1);
  }

  @Test
  void concurrentDeliveryAndRollbackPreserveOneReceiptAndNotice() throws Exception {
    var event = event("WorkCheckedIn", "WORKER");
    var transaction = new TransactionTemplate(transactions);
    transaction.executeWithoutResult(status -> { events.receive(event); status.setRollbackOnly(); });
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_events WHERE event_id = ?", Long.class, event.eventId())).isZero();
    var pool = Executors.newFixedThreadPool(6);
    try {
      List<Future<?>> tasks = new ArrayList<>();
      for (int i=0; i<6; i++) tasks.add(pool.submit(() -> events.receive(event)));
      for (var task : tasks) task.get(15, TimeUnit.SECONDS);
    } finally { pool.shutdownNow(); }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE event_id = ?", Long.class, event.eventId())).isEqualTo(1);
  }

  @Test
  void workStreamRetriesPendingWithoutBlockingNewValidEventsAndAcknowledgesDuplicates() throws Exception {
    redis.delete(STREAM);
    var first = event("WorkCheckedIn", "WORKER");
    var invalid = new HashMap<>(fields(first)); invalid.put("occurredAt", "2025-01-01T00:00:00");
    redis.opsForStream().add(StreamRecords.newRecord().in(STREAM).ofMap(invalid));
    redis.opsForStream().add(StreamRecords.newRecord().in(STREAM).ofMap(fields(first)));
    redis.opsForStream().add(StreamRecords.newRecord().in(STREAM).ofMap(fields(first)));
    consumer.poll();
    assertThat(redis.opsForStream().pending(STREAM, "notification-work-v1").getTotalPendingMessages()).isEqualTo(1);
    var second = event("WorkCompleted", "WORKER");
    redis.opsForStream().add(StreamRecords.newRecord().in(STREAM).ofMap(fields(second)));
    consumer.poll(); consumer.poll();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE event_id IN (?, ?)", Long.class, first.eventId(), second.eventId())).isEqualTo(2);
    assertThat(redis.opsForStream().pending(STREAM, "notification-work-v1").getTotalPendingMessages()).isEqualTo(1);
    // A conflicting receipt simulates a repairable consumer-side failure on an otherwise valid stream entry.
    var pending = event("WorkStarted", "OWNER");
    jdbc.update("INSERT INTO notification_events (event_id, fingerprint) VALUES (?, ?)", pending.eventId(), "0".repeat(64));
    redis.opsForStream().add(StreamRecords.newRecord().in(STREAM).ofMap(fields(pending)));
    consumer.poll();
    jdbc.update("DELETE FROM notification_events WHERE event_id = ?", pending.eventId());
    consumer.poll(); consumer.poll();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE event_id = ?", Long.class, pending.eventId())).isEqualTo(1);
    assertThat(redis.opsForStream().pending(STREAM, "notification-work-v1").getTotalPendingMessages()).isEqualTo(1);
  }

  @Test
  void olderWorkEventArrivingLaterIsRetainedAsSeparateHistory() throws Exception {
    var completed = event("WorkCompleted", "OWNER");
    events.receive(completed);
    var json = (com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(completed);
    json.put("eventId", UUID.randomUUID().toString()).put("eventType", "WorkStarted")
        .put("revision", 2).put("status", "IN_PROGRESS").put("occurredAt", "2026-10-04T09:00:00");
    events.receive(mapper.treeToValue(json, WorkNotificationEvent.class));
    assertThat(notifications.getNotifications(member(completed.workerMemberId(), "WORKER"), null, 20).content())
        .extracting(notice -> notice.type()).containsExactly("WorkStarted", "WorkCompleted");
  }
}
