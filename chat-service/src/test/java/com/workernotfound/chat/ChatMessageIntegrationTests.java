package com.workernotfound.chat;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.chat.domain.chat.dto.request.*;
import com.workernotfound.chat.domain.chat.dto.response.ChatMessageResponse;
import com.workernotfound.chat.domain.chat.event.MatchConfirmedEvent;
import com.workernotfound.chat.domain.chat.service.*;
import com.workernotfound.chat.global.security.AuthenticatedMember;
import com.workernotfound.chat.support.IntegrationTestSupport;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ChatMessageIntegrationTests extends IntegrationTestSupport {
  static final AtomicLong IDS = new AtomicLong(90000);
  @Autowired ChatRoomApplicationService rooms;
  @Autowired ChatConfirmationService confirmations;
  @Autowired ChatMessageService messages;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;

  record Room(long id, long owner, long worker) {
    AuthenticatedMember ownerIdentity() { return new AuthenticatedMember(owner, owner, "OWNER"); }
  }

  Room room(boolean confirmed) {
    long id = IDS.addAndGet(10);
    var request = new ChatRoomRequest(id, id + 1, id + 2, id + 3, "work-" + id);
    long roomId = Long.parseLong(rooms.create(UUID.randomUUID().toString(), request).chatRoomId());
    if (confirmed) confirmations.confirm(new MatchConfirmedEvent(UUID.randomUUID().toString(),
        "MatchConfirmed", LocalDateTime.now(), id, 1L, 1, id, Long.toString(roomId),
        request.workId(), request.jobPostId(), request.ownerMemberId(), request.workerMemberId()));
    return new Room(roomId, id + 2, id + 3);
  }

  String path(Room room) { return "/api/chat-rooms/me/" + room.id() + "/messages"; }

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
  void sendReplayAndConflictUseAuthenticatedSender() throws Exception {
    var room = room(true);
    String auth = token(room.owner(), "OWNER");
    String body = mapper.writeValueAsString(new ChatMessageRequest("mobile-1", "안녕하세요 😀"));
    var first = mvc.perform(post(path(room)).header("Authorization", auth)
        .contentType("application/json").content(body)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.senderMemberId").value(room.owner()))
        .andExpect(jsonPath("$.data.content").value("안녕하세요 😀"))
        .andReturn().getResponse().getContentAsString();
    long id = mapper.readTree(first).path("data").path("messageId").asLong();
    mvc.perform(post(path(room)).header("Authorization", auth)
        .contentType("application/json").content(body)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.messageId").value(id))
        .andExpect(jsonPath("$.data.createdAt").value(
            mapper.readTree(first).path("data").path("createdAt").asText()));
    mvc.perform(post(path(room)).header("Authorization", auth)
        .contentType("application/json").content(body.replace("안녕하세요", "다른 내용")))
        .andExpect(status().isConflict());
    mvc.perform(post(path(room)).header("Authorization", token(room.worker(), "WORKER"))
        .contentType("application/json").content(body)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.senderMemberId").value(room.worker()));
    assertThat(count(room)).isEqualTo(2);
    var another = room(true);
    messages.send(another.ownerIdentity(), another.id(), new ChatMessageRequest("mobile-1", "새 방"));
    assertThat(count(another)).isEqualTo(1);
  }

  @Test
  void inaccessibleRoomsRejectReadsAndWritesWithoutLeakingMessages() throws Exception {
    var confirmed = room(true);
    var pending = room(false);
    var closed = room(false);
    rooms.close(UUID.randomUUID().toString(), closed.id());
    for (var room : List.of(pending, closed)) {
      assertInaccessible(room, token(room.owner(), "OWNER"));
      assertInaccessible(room, token(room.worker(), "WORKER"));
    }
    assertInaccessible(confirmed, token(999999, "WORKER"));
    assertInaccessible(confirmed, token(confirmed.owner(), "WORKER"));
    assertInaccessible(new Room(Long.MAX_VALUE, 1, 2), token(1, "OWNER"));
    mvc.perform(get(path(confirmed))).andExpect(status().isUnauthorized());
    mvc.perform(post(path(confirmed)).contentType("application/json")
        .content("{\"clientMessageId\":\"key\",\"content\":\"hello\"}"))
        .andExpect(status().isUnauthorized());
    assertThat(count(pending)).isZero();
    assertThat(count(closed)).isZero();
  }

  void assertInaccessible(Room room, String auth) throws Exception {
    mvc.perform(get(path(room)).header("Authorization", auth)).andExpect(status().isNotFound());
    mvc.perform(post(path(room)).header("Authorization", auth).contentType("application/json")
        .content("{\"clientMessageId\":\"key\",\"content\":\"hello\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void historyCursorHasNoDuplicatesWhenNewMessagesArrive() throws Exception {
    var room = room(true);
    var identity = room.ownerIdentity();
    var ids = new ArrayList<Long>();
    for (int i = 0; i < 5; i++) ids.add(messages.send(identity, room.id(),
        new ChatMessageRequest("key-" + i, "메시지 " + i)).messageId());
    var first = messages.getMessages(identity, room.id(), null, 2);
    assertThat(first.content()).extracting(ChatMessageResponse::messageId)
        .containsExactly(ids.get(4), ids.get(3));
    assertThat(first.nextBeforeId()).isEqualTo(ids.get(3));
    messages.send(identity, room.id(), new ChatMessageRequest("new", "새 메시지"));
    var second = messages.getMessages(identity, room.id(), first.nextBeforeId(), 2);
    assertThat(second.content()).extracting(ChatMessageResponse::messageId)
        .containsExactly(ids.get(2), ids.get(1));
    var last = messages.getMessages(identity, room.id(), second.nextBeforeId(), 2);
    assertThat(last.content()).extracting(ChatMessageResponse::messageId).containsExactly(ids.get(0));
    assertThat(last.hasNext()).isFalse();
    assertThat(last.nextBeforeId()).isNull();
    assertThat(messages.getMessages(identity, room.id(), ids.get(0), 2).content()).isEmpty();
    var empty = room(true);
    assertThat(messages.getMessages(empty.ownerIdentity(), empty.id(), null, 20).content()).isEmpty();
    mvc.perform(get(path(room)).header("Authorization", token(room.worker(), "WORKER"))
        .param("beforeId", ids.get(1).toString()).param("size", "1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].messageId").value(ids.get(0)))
        .andExpect(jsonPath("$.data.hasNext").value(false));
  }

  @Test
  void invalidRequestsNeverPersistMessagesAndOpenApiExposesEndpoints() throws Exception {
    var room = room(true);
    String auth = token(room.owner(), "OWNER");
    for (var request : List.of(new ChatMessageRequest("key", " "),
        new ChatMessageRequest("key", "a".repeat(2001)), new ChatMessageRequest("bad key", "hi"),
        new ChatMessageRequest("한글", "hi"), new ChatMessageRequest("a".repeat(129), "hi"),
        new ChatMessageRequest(null, "hi"), new ChatMessageRequest("key", null))) {
      mvc.perform(post(path(room)).header("Authorization", auth).contentType("application/json")
          .content(mapper.writeValueAsString(request))).andExpect(status().isBadRequest());
    }
    for (String size : List.of("0", "101", "-1"))
      mvc.perform(get(path(room)).header("Authorization", auth).param("size", size))
          .andExpect(status().isBadRequest());
    mvc.perform(get(path(room)).header("Authorization", auth).param("beforeId", "0"))
        .andExpect(status().isBadRequest());
    assertThat(count(room)).isZero();
    mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/chat-rooms/me/{chatRoomId}/messages'].post").exists())
        .andExpect(jsonPath("$.paths['/api/chat-rooms/me/{chatRoomId}/messages'].get").exists());
  }

  @Test
  void simultaneousRetriesPersistExactlyOneMessage() throws Exception {
    var room = room(true);
    var executor = Executors.newFixedThreadPool(8);
    var start = new CountDownLatch(1);
    try {
      var futures = new ArrayList<Future<Long>>();
      for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> {
        start.await();
        return messages.send(room.ownerIdentity(), room.id(),
            new ChatMessageRequest("same-key", "같은 메시지")).messageId();
      }));
      start.countDown();
      var ids = new HashSet<Long>();
      for (var future : futures) ids.add(future.get(20, TimeUnit.SECONDS));
      assertThat(ids).hasSize(1);
      assertThat(count(room)).isEqualTo(1);
    } finally { executor.shutdownNow(); }
  }

  @Test
  void simultaneousDistinctMessagesAreAllStoredAndCaseSensitiveKeysRemainDistinct() throws Exception {
    var room = room(true);
    var executor = Executors.newFixedThreadPool(8);
    var start = new CountDownLatch(1);
    try {
      var futures = new ArrayList<Future<Long>>();
      for (int i = 0; i < 8; i++) {
        String key = "key-" + i;
        futures.add(executor.submit(() -> {
          start.await();
          return messages.send(room.ownerIdentity(), room.id(),
              new ChatMessageRequest(key, key)).messageId();
        }));
      }
      start.countDown();
      var ids = new HashSet<Long>();
      for (var future : futures) ids.add(future.get(20, TimeUnit.SECONDS));
      assertThat(ids).hasSize(8);
      messages.send(room.ownerIdentity(), room.id(), new ChatMessageRequest("KEY-0", "uppercase"));
      assertThat(count(room)).isEqualTo(9);
      assertThat(messages.getMessages(room.ownerIdentity(), room.id(), null, 100).content())
          .extracting(ChatMessageResponse::messageId).isSortedAccordingTo(Comparator.reverseOrder());
    } finally { executor.shutdownNow(); }
  }

  long count(Room room) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE chat_room_id = ?",
        Long.class, room.id());
  }
}
