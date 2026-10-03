package com.workernotfound.chat;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.chat.domain.chat.dto.request.ChatRoomRequest;
import com.workernotfound.chat.domain.chat.event.MatchConfirmedEvent;
import com.workernotfound.chat.domain.chat.service.*;
import com.workernotfound.chat.external.redis.ChatConfirmationConsumer;
import com.workernotfound.chat.global.exception.BusinessException;
import com.workernotfound.chat.support.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
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
class ChatConfirmationIntegrationTests extends IntegrationTestSupport {
  static final AtomicLong IDS = new AtomicLong(50000);
  static final String STREAM = "matching:domain-events";
  @Autowired ChatRoomApplicationService rooms;
  @Autowired ChatConfirmationService confirmations;
  @Autowired ChatConfirmationConsumer consumer;
  @Autowired StringRedisTemplate redis;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;

  ChatRoomRequest request(long id) {
    return new ChatRoomRequest(id, id + 1, id + 2, id + 3, String.valueOf(id + 4));
  }

  String create(ChatRoomRequest request) {
    return rooms.create(UUID.randomUUID().toString(), request).chatRoomId();
  }

  MatchConfirmedEvent event(ChatRoomRequest request, String roomId, long revision) {
    return new MatchConfirmedEvent(UUID.randomUUID().toString(), "MatchConfirmed",
        LocalDateTime.of(2026, 9, 22, 12, 0), request.matchingId(), revision, 1,
        request.matchingId(), roomId, request.workId(), request.jobPostId(),
        request.ownerMemberId(), request.workerMemberId());
  }

  Map<String, String> fields(MatchConfirmedEvent event) throws Exception {
    return Map.of("eventId", event.eventId(), "eventType", event.eventType(),
        "aggregateType", "MATCHING", "aggregateId", event.aggregateId().toString(),
        "revision", event.revision().toString(), "version", event.version().toString(),
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
        "chat-test-jwt-secret-for-integration-only".getBytes(StandardCharsets.UTF_8),
        "HmacSHA256"));
    return "Bearer " + unsigned + "." + encoder.encodeToString(
        mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void onlyConfirmedParticipantsCanReadRooms() throws Exception {
    var request = request(IDS.incrementAndGet());
    String id = create(request);
    String owner = token(request.ownerMemberId(), "OWNER");
    String worker = token(request.workerMemberId(), "WORKER");
    mvc.perform(get("/api/chat-rooms/me/" + id).header("Authorization", owner))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/chat-rooms/me").header("Authorization", worker))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(0));
    confirmations.confirm(event(request, id, 2));
    for (String authorization : List.of(owner, worker)) {
      mvc.perform(get("/api/chat-rooms/me/" + id).header("Authorization", authorization))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.chatRoomId").value(Long.valueOf(id)))
          .andExpect(jsonPath("$.data.workId").value(request.workId()));
      mvc.perform(get("/api/chat-rooms/me").param("size", "1")
              .header("Authorization", authorization))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.totalElements").value(1))
          .andExpect(jsonPath("$.data.content[0].chatRoomId").value(Long.valueOf(id)));
      mvc.perform(get("/api/chat-rooms/me").param("page", "1")
              .header("Authorization", authorization))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content").isEmpty());
    }
    for (String authorization : List.of(token(999999, "WORKER"),
        token(request.ownerMemberId(), "WORKER"))) {
      mvc.perform(get("/api/chat-rooms/me/" + id).header("Authorization", authorization))
          .andExpect(status().isNotFound());
    }
    assertThatThrownBy(() -> rooms.close(UUID.randomUUID().toString(), Long.valueOf(id)))
        .isInstanceOf(BusinessException.class);
    mvc.perform(get("/api/chat-rooms/me/" + id).header("Authorization", owner))
        .andExpect(status().isOk());
  }

  @Test
  void securityAndOpenApi() throws Exception {
    mvc.perform(get("/api/chat-rooms/me")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/chat-rooms/me").header("Authorization", "Bearer invalid"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/chat-rooms/me").header("Authorization", token(1, "ADMIN")))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/chat-rooms/me").param("size", "101")
        .header("Authorization", token(1, "OWNER"))).andExpect(status().isBadRequest());
    mvc.perform(get("/api/chat-rooms/me").param("page", "-1")
        .header("Authorization", token(1, "OWNER"))).andExpect(status().isBadRequest());
    mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
  }

  @Test
  void streamReplaysHistoricalAndPendingDeliveries() throws Exception {
    var request = request(IDS.incrementAndGet());
    String id = create(request);
    var first = event(request, id, 3);
    var firstRecord = redis.opsForStream().add(STREAM, fields(first));
    consumer.poll();
    var pending = event(request, id, 4);
    var pendingRecord = redis.opsForStream().add(STREAM, fields(pending));
    redis.opsForStream().read(Consumer.from("chat-confirmation-v1", "chat-confirmation"),
        StreamReadOptions.empty(), StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
    consumer.poll();
    redis.opsForStream().add(STREAM, fields(first));
    consumer.poll();
    assertThat(jdbc.queryForObject(
        "SELECT confirmation_revision FROM chat_rooms WHERE id = ?", Long.class, id))
        .isEqualTo(4);
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM chat_confirmation_events WHERE event_id = ?", Long.class,
        first.eventId())).isEqualTo(1);
    assertThat(redis.opsForStream().pending(STREAM, "chat-confirmation-v1",
        org.springframework.data.domain.Range.closed(firstRecord.getValue(),
            firstRecord.getValue()), 10)).isEmpty();
    assertThat(redis.opsForStream().pending(STREAM, "chat-confirmation-v1",
        org.springframework.data.domain.Range.closed(pendingRecord.getValue(),
            pendingRecord.getValue()), 10)).isEmpty();
  }

  @Test
  void canceledOldAttemptAndInvalidEventsNeverExposeRooms() throws Exception {
    var request = request(IDS.incrementAndGet());
    String oldId = create(request);
    rooms.close(UUID.randomUUID().toString(), Long.valueOf(oldId));
    String replacement = create(request);
    confirmations.confirm(event(request, oldId, 1));
    assertThat(jdbc.queryForObject(
        "SELECT confirmed_at IS NULL FROM chat_rooms WHERE id = ?", Boolean.class,
        replacement)).isTrue();
    var wrong = request(IDS.incrementAndGet());
    var mismatch = event(wrong, replacement, 2);
    assertThatThrownBy(() -> confirmations.confirm(mismatch))
        .isInstanceOf(IllegalArgumentException.class);
    var valid = event(request, replacement, 2);
    confirmations.confirm(valid);
    var changed = new MatchConfirmedEvent(valid.eventId(), valid.eventType(),
        valid.occurredAt(), valid.aggregateId(), 9L, valid.version(),
        valid.matchingId(), valid.chatRoomId(), valid.workId(), valid.jobPostId(),
        valid.ownerMemberId(), valid.workerMemberId());
    assertThatThrownBy(() -> confirmations.confirm(changed))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(jdbc.queryForObject(
        "SELECT confirmation_revision FROM chat_rooms WHERE id = ?", Long.class,
        replacement)).isEqualTo(2);
    mvc.perform(get("/api/chat-rooms/me/" + oldId)
        .header("Authorization", token(request.ownerMemberId(), "OWNER")))
        .andExpect(status().isNotFound());
  }

  @Test
  void malformedEnvelopeStaysPendingWhileFollowingEventConfirms() throws Exception {
    var request = request(IDS.incrementAndGet());
    String id = create(request);
    var malformed = new HashMap<>(fields(event(request, id, 2)));
    malformed.put("revision", "900");
    var bad = redis.opsForStream().add(STREAM, malformed);
    redis.opsForStream().add(STREAM, fields(event(request, id, 2)));
    consumer.poll();
    assertThat(jdbc.queryForObject(
        "SELECT confirmed_at IS NOT NULL FROM chat_rooms WHERE id = ?", Boolean.class,
        id)).isTrue();
    assertThat(redis.opsForStream().pending(STREAM, "chat-confirmation-v1",
        org.springframework.data.domain.Range.closed(bad.getValue(), bad.getValue()),
        10)).hasSize(1);
    redis.opsForStream().acknowledge(STREAM, "chat-confirmation-v1", bad);
  }

  @Test
  void concurrentDuplicateDeliveryRecordsOneReceipt() throws Exception {
    var request = request(IDS.incrementAndGet());
    String id = create(request);
    var event = event(request, id, 2);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      var tasks = new ArrayList<Future<?>>();
      for (int i = 0; i < 4; i++) tasks.add(pool.submit(() -> confirmations.confirm(event)));
      for (var task : tasks) task.get(10, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM chat_confirmation_events WHERE event_id = ?", Long.class,
        event.eventId())).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "SELECT confirmation_revision FROM chat_rooms WHERE id = ?", Long.class,
        id)).isEqualTo(2);
  }

  @Test
  void confirmationReceiptUsesFixedV1Fields() throws Exception {
    var request = request(IDS.incrementAndGet());
    String id = create(request);
    var event = event(request, id, 2);
    confirmations.confirm(event);

    var fields = List.of(event.eventId(), "MatchConfirmed", "2026-09-22T12:00:00",
        request.matchingId().toString(), "2", "1", request.matchingId().toString(), id,
        request.workId(), request.jobPostId().toString(),
        request.ownerMemberId().toString(), request.workerMemberId().toString());
    String payload = fields.stream().map(value -> value.length() + ":" + value)
        .collect(Collectors.joining());
    String expected = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
    assertThat(jdbc.queryForObject(
        "SELECT fingerprint FROM chat_confirmation_events WHERE event_id = ?", String.class,
        event.eventId())).isEqualTo(expected);
  }
}
