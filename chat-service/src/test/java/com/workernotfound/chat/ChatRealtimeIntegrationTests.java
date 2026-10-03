package com.workernotfound.chat;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.chat.domain.chat.dto.request.*;
import com.workernotfound.chat.domain.chat.dto.response.ChatMessageResponse;
import com.workernotfound.chat.domain.chat.event.*;
import com.workernotfound.chat.domain.chat.service.*;
import com.workernotfound.chat.external.redis.ChatMessageNotifications;
import com.workernotfound.chat.global.security.AuthenticatedMember;
import com.workernotfound.chat.support.IntegrationTestSupport;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.*;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ChatRealtimeIntegrationTests extends IntegrationTestSupport {
  static final AtomicLong IDS = new AtomicLong(200000);
  @Value("${local.server.port}") int port;
  @Autowired ChatRoomApplicationService rooms;
  @Autowired ChatConfirmationService confirmations;
  @Autowired ChatMessageService messages;
  @Autowired ObjectMapper mapper;
  @Autowired MockMvc mvc;
  @Autowired SimpleBrokerMessageHandler broker;
  @Autowired PlatformTransactionManager transactions;
  @MockitoSpyBean StringRedisTemplate redis;
  WebSocketStompClient client;

  @BeforeEach
  void startClient() {
    client = new WebSocketStompClient(new StandardWebSocketClient());
    client.setMessageConverter(new StringMessageConverter() {
      @Override
      protected boolean supportsMimeType(org.springframework.messaging.MessageHeaders headers) {
        return true;
      }
    });
  }

  @AfterEach
  void stopClient() { client.stop(); }

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

  String token(long memberId, String role, long seconds) throws Exception {
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String header = encoder.encodeToString(
        "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String payload = mapper.writeValueAsString(Map.of("authAccountId", memberId,
        "memberId", memberId, "role", role,
        "exp", Instant.now().plusSeconds(seconds).getEpochSecond()));
    String unsigned = header + "." + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(
        "chat-test-jwt-secret-for-integration-only".getBytes(StandardCharsets.UTF_8),
        "HmacSHA256"));
    return "Bearer " + unsigned + "." + encoder.encodeToString(
        mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
  }



  static class Frames extends StompSessionHandlerAdapter {
    final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    final CompletableFuture<String> error = new CompletableFuture<>();
    @Override public Type getPayloadType(StompHeaders headers) { return String.class; }
    @Override public void handleFrame(StompHeaders headers, Object payload) {
      error.complete(String.valueOf(payload));
    }
    @Override public void handleException(StompSession session, StompCommand command,
        StompHeaders headers, byte[] payload, Throwable exception) {
      error.complete("frame conversion failed: " + exception.getClass().getSimpleName());
    }
    @Override public void handleTransportError(StompSession session, Throwable exception) {
      error.complete("transport closed");
    }
    StompFrameHandler messageHandler() {
      return new StompFrameHandler() {
        @Override public Type getPayloadType(StompHeaders headers) { return String.class; }
        @Override public void handleFrame(StompHeaders headers, Object payload) {
          messages.add(String.valueOf(payload));
        }
      };
    }
  }

  StompSession connect(String token, Frames frames) throws Exception {
    var headers = new StompHeaders();
    if (token != null) headers.add("Authorization", token);
    var connected = client.connectAsync("ws://localhost:" + port + "/api/chat-rooms/ws",
        new org.springframework.web.socket.WebSocketHttpHeaders(), headers, frames);
    var rejected = frames.error.thenApply(error -> { throw new IllegalStateException(error); });
    return (StompSession) CompletableFuture.anyOf(connected, rejected).get(10, TimeUnit.SECONDS);
  }

  String destination(Room room) { return "/topic/chat-rooms/" + room.id(); }

  void awaitSubscriptions(Room room, int expected) throws Exception {
    var lookup = MessageBuilder.withPayload(new byte[0])
        .setHeader(SimpMessageHeaderAccessor.DESTINATION_HEADER, destination(room))
        .setHeader(SimpMessageHeaderAccessor.MESSAGE_TYPE_HEADER, SimpMessageType.MESSAGE).build();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (broker.getSubscriptionRegistry().findSubscriptions(lookup).size() != expected
        && System.nanoTime() < deadline) Thread.sleep(10);
    assertThat(broker.getSubscriptionRegistry().findSubscriptions(lookup)).hasSize(expected);
  }

  @Test
  void bothParticipantsReceiveCommittedHintsAndReconnectCanRecoverMessages() throws Exception {
    var room = room(true);
    var ownerFrames = new Frames();
    var workerFrames = new Frames();
    var owner = connect(token(room.owner(), "OWNER", 300), ownerFrames);
    var worker = connect(token(room.worker(), "WORKER", 300), workerFrames);
    owner.subscribe(destination(room), ownerFrames.messageHandler());
    worker.subscribe(destination(room), workerFrames.messageHandler());
    awaitSubscriptions(room, 2);
    var sent = messages.send(room.ownerIdentity(), room.id(), new ChatMessageRequest("one", "첫 메시지"));
    for (var frames : List.of(ownerFrames, workerFrames)) {
      String hint = frames.messages.poll(5, TimeUnit.SECONDS);
      assertThat(hint).isNotNull();
      assertThat(mapper.readTree(hint).path("messageId").asLong()).isEqualTo(sent.messageId());
      assertThat(mapper.readTree(hint).has("content")).isFalse();
    }
    worker.disconnect();
    var missed = messages.send(room.ownerIdentity(), room.id(), new ChatMessageRequest("two", "놓친 메시지"));
    mvc.perform(get(path(room) + "/sync").header("Authorization", token(room.worker(), "WORKER", 300))
        .param("afterId", sent.messageId().toString()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].messageId").value(missed.messageId()))
        .andExpect(jsonPath("$.data.nextAfterId").value(missed.messageId()));
    var reconnectFrames = new Frames();
    var reconnected = connect(token(room.worker(), "WORKER", 300), reconnectFrames);
    reconnected.subscribe(destination(room), reconnectFrames.messageHandler());
    awaitSubscriptions(room, 2);
    redis.convertAndSend(ChatMessageNotifications.CHANNEL, room.id() + ":" + missed.messageId());
    assertThat(reconnectFrames.messages.poll(5, TimeUnit.SECONDS)).isNotNull();
    owner.disconnect();
    reconnected.disconnect();
  }

  @Test
  void unauthorizedConnectionsSubscriptionsAndClientBroadcastsAreRejected() throws Exception {
    var room = room(true);
    for (String auth : List.of("Bearer invalid", token(room.owner(), "OWNER", -1))) {
      var frames = new Frames();
      assertThatThrownBy(() -> connect(auth, frames)).isInstanceOf(ExecutionException.class);
      assertThat(frames.error.get(1, TimeUnit.SECONDS)).isEqualTo("Chat request rejected");
    }
    assertThatThrownBy(() -> connect(null, new Frames())).isInstanceOf(ExecutionException.class);
    var pending = room(false);
    for (String destination : List.of(destination(room), "/topic/chat-rooms/*", "/topic/chat-rooms/**",
        "/topic/chat-rooms/9223372036854775808", "/user/queue/messages")) {
      var frames = new Frames();
      var stranger = connect(token(999999, "WORKER", 300), frames);
      stranger.subscribe(destination, frames.messageHandler());
      assertThat(frames.error.get(5, TimeUnit.SECONDS)).isEqualTo("Chat request rejected");
    }
    var frames = new Frames();
    var owner = connect(token(pending.owner(), "OWNER", 300), frames);
    owner.subscribe(destination(pending), frames.messageHandler());
    assertThat(frames.error.get(5, TimeUnit.SECONDS)).isEqualTo("Chat request rejected");
    var closed = room(false);
    rooms.close(UUID.randomUUID().toString(), closed.id());
    frames = new Frames();
    owner = connect(token(closed.owner(), "OWNER", 300), frames);
    owner.subscribe(destination(closed), frames.messageHandler());
    assertThat(frames.error.get(5, TimeUnit.SECONDS)).isEqualTo("Chat request rejected");
    frames = new Frames();
    owner = connect(token(room.owner(), "WORKER", 300), frames);
    owner.subscribe(destination(room), frames.messageHandler());
    assertThat(frames.error.get(5, TimeUnit.SECONDS)).isEqualTo("Chat request rejected");
    frames = new Frames();
    owner = connect(token(room.owner(), "OWNER", 300), frames);
    owner.send(destination(room), "forged message");
    assertThat(frames.error.get(5, TimeUnit.SECONDS)).isEqualTo("Chat request rejected");
  }

  @Test
  void foreignBrowserOriginCannotOpenSocket() throws Exception {
    var room = room(true);
    var http = new org.springframework.web.socket.WebSocketHttpHeaders();
    http.setOrigin("https://untrusted.example");
    var headers = new StompHeaders();
    headers.add("Authorization", token(room.owner(), "OWNER", 300));
    assertThatThrownBy(() -> client.connectAsync("ws://localhost:" + port + "/api/chat-rooms/ws",
        http, headers, new Frames()).get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
  }

  @Test
  void expiredSessionStopsReceivingEvenWithoutFurtherInboundCommands() throws Exception {
    var room = room(true);
    var frames = new Frames();
    var owner = connect(token(room.owner(), "OWNER", 2), frames);
    owner.subscribe(destination(room), frames.messageHandler());
    awaitSubscriptions(room, 1);
    Thread.sleep(2100);
    messages.send(room.ownerIdentity(), room.id(), new ChatMessageRequest("expired", "비공개"));
    assertThat(frames.messages.poll(300, TimeUnit.MILLISECONDS)).isNull();
    owner.disconnect();
  }

  @Test
  void rollbackNeverPublishesAndRedisFailureDoesNotUndoCommittedMessage() throws Exception {
    var room = room(true);
    var frames = new Frames();
    var owner = connect(token(room.owner(), "OWNER", 300), frames);
    owner.subscribe(destination(room), frames.messageHandler());
    awaitSubscriptions(room, 1);
    new TransactionTemplate(transactions).executeWithoutResult(tx -> {
      messages.send(room.ownerIdentity(), room.id(), new ChatMessageRequest("rollback", "취소"));
      tx.setRollbackOnly();
    });
    assertThat(frames.messages.poll(200, TimeUnit.MILLISECONDS)).isNull();
    assertThat(messages.getNewMessages(room.ownerIdentity(), room.id(), 0L, 100).content()).isEmpty();
    doThrow(new IllegalStateException("simulated redis failure")).when(redis)
        .convertAndSend(eq(ChatMessageNotifications.CHANNEL), anyString());
    var sent = messages.send(room.ownerIdentity(), room.id(), new ChatMessageRequest("fallback", "보관"));
    assertThat(messages.getNewMessages(room.ownerIdentity(), room.id(), 0L, 100).content())
        .extracting(ChatMessageResponse::messageId).containsExactly(sent.messageId());
    owner.disconnect();
  }

  @Test
  void syncPagesKeepAscendingCursorAndValidateParticipantAndBounds() throws Exception {
    var room = room(true);
    var ids = new ArrayList<Long>();
    for (int i = 0; i < 5; i++) ids.add(messages.send(room.ownerIdentity(), room.id(),
        new ChatMessageRequest("sync-" + i, "메시지 " + i)).messageId());
    var first = messages.getNewMessages(room.ownerIdentity(), room.id(), 0L, 2);
    assertThat(first.content()).extracting(ChatMessageResponse::messageId).containsExactly(ids.get(0), ids.get(1));
    assertThat(first.hasNext()).isTrue();
    var second = messages.getNewMessages(room.ownerIdentity(), room.id(), first.nextAfterId(), 2);
    assertThat(second.content()).extracting(ChatMessageResponse::messageId).containsExactly(ids.get(2), ids.get(3));
    var third = messages.getNewMessages(room.ownerIdentity(), room.id(), second.nextAfterId(), 2);
    assertThat(third.content()).extracting(ChatMessageResponse::messageId).containsExactly(ids.get(4));
    assertThat(third.hasNext()).isFalse();
    var empty = messages.getNewMessages(room.ownerIdentity(), room.id(), third.nextAfterId(), 2);
    assertThat(empty.content()).isEmpty();
    assertThat(empty.nextAfterId()).isEqualTo(ids.get(4));
    String auth = token(room.worker(), "WORKER", 300);
    mvc.perform(get(path(room) + "/sync").header("Authorization", auth).param("size", "1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].messageId").value(ids.get(0)))
        .andExpect(jsonPath("$.data.content[1]").doesNotExist())
        .andExpect(jsonPath("$.data.nextAfterId").value(ids.get(0)))
        .andExpect(jsonPath("$.data.hasNext").value(true));
    mvc.perform(get(path(room) + "/sync").header("Authorization", auth).param("afterId", "-1"))
        .andExpect(status().isBadRequest());
    mvc.perform(get(path(room) + "/sync").header("Authorization", auth).param("size", "101"))
        .andExpect(status().isBadRequest());
    mvc.perform(get(path(room) + "/sync").header("Authorization", token(999999, "WORKER", 300)))
        .andExpect(status().isNotFound());
  }
}
