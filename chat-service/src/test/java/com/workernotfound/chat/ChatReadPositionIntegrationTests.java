package com.workernotfound.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.chat.domain.chat.dto.request.ChatMessageRequest;
import com.workernotfound.chat.domain.chat.dto.request.ChatReadPositionRequest;
import com.workernotfound.chat.domain.chat.dto.request.ChatRoomRequest;
import com.workernotfound.chat.domain.chat.event.MatchConfirmedEvent;
import com.workernotfound.chat.domain.chat.repository.ChatReadPositionRepository;
import com.workernotfound.chat.domain.chat.service.ChatConfirmationService;
import com.workernotfound.chat.domain.chat.service.ChatMessageService;
import com.workernotfound.chat.domain.chat.service.ChatReadPositionService;
import com.workernotfound.chat.domain.chat.service.ChatRoomApplicationService;
import com.workernotfound.chat.global.account.AccountGateService;
import com.workernotfound.chat.global.exception.BusinessException;
import com.workernotfound.chat.global.security.AuthenticatedMember;
import com.workernotfound.chat.support.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class ChatReadPositionIntegrationTests extends IntegrationTestSupport {
  private static final AtomicLong IDS = new AtomicLong(4_000_000);
  private static final String ROOM_LOCK_WAIT = """
      SELECT 1
      FROM performance_schema.data_locks requested
      JOIN performance_schema.threads requester ON requester.THREAD_ID = requested.THREAD_ID
      WHERE requester.PROCESSLIST_ID = ?
        AND requested.OBJECT_NAME = 'chat_rooms'
        AND requested.LOCK_TYPE = 'RECORD'
        AND requested.LOCK_STATUS = 'WAITING'
      """;
  @Autowired ChatRoomApplicationService rooms;
  @Autowired ChatConfirmationService confirmations;
  @Autowired ChatMessageService messages;
  @Autowired ChatReadPositionService reads;
  @Autowired ChatReadPositionRepository positions;
  @Autowired AccountGateService gates;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired PlatformTransactionManager transactionManager;

  record Room(long id, long owner, long worker) {
    AuthenticatedMember ownerIdentity() { return new AuthenticatedMember(owner, owner, "OWNER"); }
    AuthenticatedMember workerIdentity() { return new AuthenticatedMember(worker, worker, "WORKER"); }
  }

  @Test
  void participantsAdvanceTheirOwnPositionsAndKeepReplaysStable() throws Exception {
    var room = room(true);
    long first = send(room, room.ownerIdentity());
    long second = send(room, room.workerIdentity());
    String ownerToken = token(room.owner(), "OWNER");

    mvc.perform(put(path(room)).header("Authorization", ownerToken)
        .contentType("application/json").content(body(first)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.chatRoomId").value(room.id()))
        .andExpect(jsonPath("$.data.lastReadMessageId").value(first));
    mvc.perform(put(path(room)).header("Authorization", ownerToken)
        .contentType("application/json").content(body(second)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.lastReadMessageId").value(second));
    var advanced = positions.findByChatRoomIdAndMemberId(room.id(), room.owner()).orElseThrow();
    for (long id : List.of(second, first)) {
      mvc.perform(put(path(room)).header("Authorization", ownerToken)
          .contentType("application/json").content(body(id)))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data.lastReadMessageId").value(second));
      var replay = positions.findByChatRoomIdAndMemberId(room.id(), room.owner()).orElseThrow();
      assertThat(replay.getReadAt()).isEqualTo(advanced.getReadAt());
    }
    mvc.perform(put(path(room)).header("Authorization", token(room.worker(), "WORKER"))
        .contentType("application/json").content(body(first)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.lastReadMessageId").value(first));
    assertThat(positions.findByChatRoomIdAndMemberId(room.id(), room.owner()).orElseThrow()
        .getLastReadMessageId()).isEqualTo(second);
    assertThat(positions.findByChatRoomIdAndMemberId(room.id(), room.worker()).orElseThrow()
        .getLastReadMessageId()).isEqualTo(first);
    assertThat(positionCount(room)).isEqualTo(2);
  }

  @Test
  void sendingAndFetchingMessagesDoNotImplicitlyMarkThemRead() throws Exception {
    var room = room(true);
    send(room, room.ownerIdentity());
    String auth = token(room.owner(), "OWNER");
    String messagesPath = "/api/chat-rooms/me/" + room.id() + "/messages";

    mvc.perform(get(messagesPath).header("Authorization", auth)).andExpect(status().isOk());
    mvc.perform(get(messagesPath + "/sync").header("Authorization", auth)).andExpect(status().isOk());
    mvc.perform(get("/api/chat-rooms/me/" + room.id()).header("Authorization", auth))
        .andExpect(status().isOk());

    assertThat(positionCount(room)).isZero();
  }

  @Test
  void rejectsInaccessibleRoomsBeforeInspectingMessageExistence() throws Exception {
    var confirmed = room(true);
    long messageId = send(confirmed, confirmed.ownerIdentity());
    var pending = room(false);
    var closed = room(false);
    rooms.close(UUID.randomUUID().toString(), closed.id());
    for (var inaccessible : List.of(pending, closed)) {
      assertHidden(inaccessible, token(inaccessible.owner(), "OWNER"), messageId);
      assertHidden(inaccessible, token(inaccessible.worker(), "WORKER"), messageId);
    }
    assertHidden(confirmed, token(IDS.incrementAndGet(), "OWNER"), messageId);
    assertHidden(confirmed, token(confirmed.owner(), "WORKER"), messageId);
    assertHidden(confirmed, token(confirmed.worker(), "OWNER"), messageId);
    assertHidden(new Room(Long.MAX_VALUE, 1, 2), token(1, "OWNER"), messageId);
    assertThat(positionCount(confirmed)).isZero();
  }

  @Test
  void rejectsMissingAndOtherRoomMessagesWithoutChangingExistingPosition() throws Exception {
    var room = room(true);
    long first = send(room, room.ownerIdentity());
    var other = room(true);
    long otherMessage = send(other, other.ownerIdentity());
    reads.advanceReadPosition(room.ownerIdentity(), room.id(), new ChatReadPositionRequest(first));
    var original = positions.findByChatRoomIdAndMemberId(room.id(), room.owner()).orElseThrow();

    for (long messageId : List.of(Long.MAX_VALUE, otherMessage))
      mvc.perform(put(path(room)).header("Authorization", token(room.owner(), "OWNER"))
          .contentType("application/json").content(body(messageId)))
          .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CHAT-404-002"));

    var unchanged = positions.findByChatRoomIdAndMemberId(room.id(), room.owner()).orElseThrow();
    assertThat(unchanged.getLastReadMessageId()).isEqualTo(first);
    assertThat(unchanged.getReadAt()).isEqualTo(original.getReadAt());
    assertThat(positionCount(other)).isZero();
    var empty = room(true);
    mvc.perform(put(path(empty)).header("Authorization", token(empty.owner(), "OWNER"))
        .contentType("application/json").content(body(first))).andExpect(status().isNotFound());
    assertThat(positionCount(empty)).isZero();
  }

  @Test
  void validatesInputAndRequiresAuthentication() throws Exception {
    var room = room(true);
    String auth = token(room.owner(), "OWNER");
    for (String invalid : List.of("{}", "{\"lastReadMessageId\":null}", body(0), body(-1), "{", "null"))
      mvc.perform(put(path(room)).header("Authorization", auth)
          .contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
    mvc.perform(put("/api/chat-rooms/me/0/read-position").header("Authorization", auth)
        .contentType("application/json").content(body(1))).andExpect(status().isBadRequest());
    mvc.perform(put(path(room)).header("Authorization", auth)
        .contentType("application/json")).andExpect(status().isBadRequest());
    mvc.perform(put(path(room)).contentType("application/json").content(body(1)))
        .andExpect(status().isUnauthorized());
    assertThat(positionCount(room)).isZero();
  }

  @Test
  void preparedAndWithdrawnMembersCannotUpdateEvenThroughService() throws Exception {
    var room = room(true);
    long first = send(room, room.workerIdentity());
    long second = send(room, room.workerIdentity());
    reads.advanceReadPosition(room.ownerIdentity(), room.id(), new ChatReadPositionRequest(first));
    String command = UUID.randomUUID().toString();
    assertThat(gates.transition(room.owner(), command, "prepare").state()).isEqualTo("PREPARED");

    assertGateBlocked(room, second);
    assertThat(gates.transition(room.owner(), command, "commit").state()).isEqualTo("COMMITTED");
    assertGateBlocked(room, second);

    assertThat(positions.findByChatRoomIdAndMemberId(room.id(), room.owner()).orElseThrow()
        .getLastReadMessageId()).isEqualTo(first);
  }

  @Test
  void openApiDocumentsReadPositionAndSwaggerRemainsAccessible() throws Exception {
    mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/chat-rooms/me/{chatRoomId}/read-position'].put").exists())
        .andExpect(jsonPath("$.components.schemas.ChatReadPositionRequest.required[0]")
            .value("lastReadMessageId"));
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
  }

  @Test
  void concurrentDevicesCreateOnePositionAndConvergeToTheLargestConfirmedId() throws Exception {
    var room = room(true);
    long first = send(room, room.workerIdentity());
    long second = send(room, room.workerIdentity());
    long third = send(room, room.workerIdentity());
    var tasks = new ArrayList<Callable<Long>>();
    for (long messageId : List.of(third, first, second, first, third, second, third, first))
      tasks.add(() -> reads.advanceReadPosition(room.ownerIdentity(), room.id(),
          new ChatReadPositionRequest(messageId)).lastReadMessageId());

    concurrently(tasks);

    assertThat(positionCount(room)).isEqualTo(1);
    var persisted = positions.findByChatRoomIdAndMemberId(room.id(), room.owner()).orElseThrow();
    assertThat(persisted.getLastReadMessageId()).isEqualTo(third);
    var retry = reads.advanceReadPosition(room.ownerIdentity(), room.id(), new ChatReadPositionRequest(first));
    assertThat(retry.lastReadMessageId()).isEqualTo(third);
    assertThat(retry.readAt()).isEqualTo(persisted.getReadAt());
  }

  @Test
  void simultaneousParticipantUpdatesKeepSeparatePositions() throws Exception {
    var room = room(true);
    long first = send(room, room.ownerIdentity());
    long second = send(room, room.workerIdentity());

    concurrently(List.of(
        () -> reads.advanceReadPosition(room.ownerIdentity(), room.id(), new ChatReadPositionRequest(second)),
        () -> reads.advanceReadPosition(room.workerIdentity(), room.id(), new ChatReadPositionRequest(first))));

    assertThat(positionCount(room)).isEqualTo(2);
    assertThat(positions.findByChatRoomIdAndMemberId(room.id(), room.owner()).orElseThrow()
        .getLastReadMessageId()).isEqualTo(second);
    assertThat(positions.findByChatRoomIdAndMemberId(room.id(), room.worker()).orElseThrow()
        .getLastReadMessageId()).isEqualTo(first);
  }

  @Test
  void concurrentSendCannotMoveReadPositionPastTheExplicitlyConfirmedMessage() throws Exception {
    var room = room(true);
    long seen = send(room, room.workerIdentity());

    var results = concurrently(List.<Callable<Long>>of(
        () -> reads.advanceReadPosition(room.ownerIdentity(), room.id(), new ChatReadPositionRequest(seen))
            .lastReadMessageId(),
        () -> send(room, room.workerIdentity())));

    assertThat(results.get(0)).isEqualTo(seen);
    assertThat(results.get(1)).isGreaterThan(seen);
    assertThat(positions.findByChatRoomIdAndMemberId(room.id(), room.owner()).orElseThrow()
        .getLastReadMessageId()).isEqualTo(seen);
    assertThat(positions.findByChatRoomIdAndMemberId(room.id(), room.worker())).isEmpty();
  }

  @Test
  void waitsForTheRoomLockOfAnInFlightSendAndAcceptsItsMessageAfterCommit() throws Exception {
    var room = room(true);
    var stored = new CompletableFuture<Long>();
    var readerConnection = new CompletableFuture<Long>();
    var commitSend = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var sender = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
        stored.complete(send(room, room.workerIdentity()));
        awaitLatch(commitSend);
      }));
      long messageId = stored.get(10, TimeUnit.SECONDS);
      var reader = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
        readerConnection.complete(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
        return reads.advanceReadPosition(room.ownerIdentity(), room.id(), new ChatReadPositionRequest(messageId));
      }));

      // 방 잠금 전에 메시지를 조회하면 커밋 전 메시지를 찾지 못해 대기 없이 CHAT-404-002로 끝난다.
      awaitRoomLockWait(readerConnection.get(10, TimeUnit.SECONDS), reader);
      commitSend.countDown();
      sender.get(20, TimeUnit.SECONDS);

      assertThat(reader.get(20, TimeUnit.SECONDS).lastReadMessageId()).isEqualTo(messageId);
      assertThat(positions.findByChatRoomIdAndMemberId(room.id(), room.owner()).orElseThrow()
          .getLastReadMessageId()).isEqualTo(messageId);
    } finally {
      commitSend.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void rollbackLeavesNeitherAnInitialPositionNorAPartialAdvance() {
    var room = room(true);
    long first = send(room, room.workerIdentity());
    long second = send(room, room.workerIdentity());
    var transactions = new TransactionTemplate(transactionManager);

    transactions.executeWithoutResult(status -> {
      reads.advanceReadPosition(room.ownerIdentity(), room.id(), new ChatReadPositionRequest(first));
      status.setRollbackOnly();
    });
    assertThat(positionCount(room)).isZero();
    var initial = reads.advanceReadPosition(room.ownerIdentity(), room.id(), new ChatReadPositionRequest(first));
    transactions.executeWithoutResult(status -> {
      reads.advanceReadPosition(room.ownerIdentity(), room.id(), new ChatReadPositionRequest(second));
      status.setRollbackOnly();
    });

    var persisted = positions.findByChatRoomIdAndMemberId(room.id(), room.owner()).orElseThrow();
    assertThat(persisted.getLastReadMessageId()).isEqualTo(first);
    assertThat(persisted.getReadAt()).isEqualTo(initial.readAt());
  }

  <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
    var executor = Executors.newFixedThreadPool(tasks.size());
    var start = new CountDownLatch(1);
    try {
      var futures = new ArrayList<Future<T>>();
      for (var task : tasks) futures.add(executor.submit(() -> {
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("동시 실행 시작 시간 초과");
        return task.call();
      }));
      start.countDown();
      var results = new ArrayList<T>();
      for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
      return results;
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  void awaitRoomLockWait(long connectionId, Future<?> reader) throws Exception {
    try (var observer = openLockObserverConnection(); var query = observer.prepareStatement(ROOM_LOCK_WAIT)) {
      query.setLong(1, connectionId);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (System.nanoTime() < deadline) {
        assertThat(reader.isDone()).as("방 잠금을 기다리기 전에 읽음 갱신이 끝나면 안 된다").isFalse();
        try (var waiting = query.executeQuery()) {
          if (waiting.next()) return;
        }
        Thread.sleep(50);
      }
      throw new AssertionError("읽음 갱신이 전송 트랜잭션의 방 행 잠금을 기다려야 한다");
    }
  }

  void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("경합 테스트 대기 시간 초과");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("경합 테스트 대기가 중단되었습니다.", exception);
    }
  }

  void assertHidden(Room room, String auth, long messageId) throws Exception {
    mvc.perform(put(path(room)).header("Authorization", auth)
        .contentType("application/json").content(body(messageId)))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CHAT-404-001"));
    assertThat(positionCount(room)).isZero();
  }

  void assertGateBlocked(Room room, long messageId) throws Exception {
    mvc.perform(put(path(room)).header("Authorization", token(room.owner(), "OWNER"))
        .contentType("application/json").content(body(messageId))).andExpect(status().isUnauthorized());
    assertThatThrownBy(() -> reads.advanceReadPosition(room.ownerIdentity(), room.id(),
        new ChatReadPositionRequest(messageId))).isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getErrorCode().getCode()).isEqualTo("GLOBAL-403-001"));
  }

  Room room(boolean isConfirmed) {
    long id = IDS.addAndGet(10);
    var request = new ChatRoomRequest(id, id + 1, id + 2, id + 3, "work-" + id);
    long roomId = Long.parseLong(rooms.create(UUID.randomUUID().toString(), request).chatRoomId());
    if (isConfirmed) confirmations.confirm(new MatchConfirmedEvent(UUID.randomUUID().toString(),
        "MatchConfirmed", LocalDateTime.now(), id, 1L, 1, id, Long.toString(roomId),
        request.workId(), request.jobPostId(), request.ownerMemberId(), request.workerMemberId()));
    return new Room(roomId, id + 2, id + 3);
  }

  long send(Room room, AuthenticatedMember sender) {
    return messages.send(sender, room.id(), new ChatMessageRequest(UUID.randomUUID().toString(), "메시지"))
        .messageId();
  }

  long positionCount(Room room) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM chat_read_positions WHERE chat_room_id = ?",
        Long.class, room.id());
  }

  String path(Room room) { return "/api/chat-rooms/me/" + room.id() + "/read-position"; }

  String body(long messageId) { return "{\"lastReadMessageId\":" + messageId + "}"; }

  String token(long memberId, String role) throws Exception {
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String header = encoder.encodeToString(
        "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String payload = mapper.writeValueAsString(Map.of("authAccountId", memberId,
        "memberId", memberId, "role", role, "exp", Instant.now().plusSeconds(300).getEpochSecond()));
    String unsigned = header + "." + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("chat-test-jwt-secret-for-integration-only".getBytes(StandardCharsets.UTF_8),
        "HmacSHA256"));
    return "Bearer " + unsigned + "." + encoder.encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
  }
}
