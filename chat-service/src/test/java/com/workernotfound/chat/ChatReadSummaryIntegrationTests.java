package com.workernotfound.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.chat.domain.chat.dto.request.ChatMessageRequest;
import com.workernotfound.chat.domain.chat.dto.request.ChatReadPositionRequest;
import com.workernotfound.chat.domain.chat.dto.request.ChatRoomRequest;
import com.workernotfound.chat.domain.chat.event.MatchConfirmedEvent;
import com.workernotfound.chat.domain.chat.repository.ChatReadPositionRepository;
import com.workernotfound.chat.domain.chat.repository.ChatReadSummaryRepository;
import com.workernotfound.chat.domain.chat.repository.projection.ChatReadSummary;
import com.workernotfound.chat.domain.chat.service.ChatConfirmationService;
import com.workernotfound.chat.domain.chat.service.ChatMessageService;
import com.workernotfound.chat.domain.chat.service.ChatReadPositionService;
import com.workernotfound.chat.domain.chat.service.ChatRoomApplicationService;
import com.workernotfound.chat.domain.chat.service.ChatRoomQueryService;
import com.workernotfound.chat.global.account.AccountGateService;
import com.workernotfound.chat.global.security.AuthenticatedMember;
import com.workernotfound.chat.support.IntegrationTestSupport;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class ChatReadSummaryIntegrationTests extends IntegrationTestSupport {
  private static final AtomicLong IDS = new AtomicLong(6_000_000);
  @Autowired ChatRoomApplicationService rooms;
  @Autowired ChatConfirmationService confirmations;
  @Autowired ChatMessageService messages;
  @Autowired ChatReadPositionService reads;
  @Autowired ChatReadPositionRepository positions;
  @Autowired ChatReadSummaryRepository summaries;
  @Autowired ChatRoomQueryService queries;
  @Autowired AccountGateService gates;
  @Autowired ObjectMapper mapper;
  @Autowired MockMvc mvc;
  @Autowired EntityManagerFactory entityManagerFactory;
  @Autowired PlatformTransactionManager transactionManager;

  record Room(long id, long owner, long worker) {
    AuthenticatedMember ownerIdentity() { return new AuthenticatedMember(owner, owner, "OWNER"); }
    AuthenticatedMember workerIdentity() { return new AuthenticatedMember(worker, worker, "WORKER"); }
  }

  @Test
  void summarizesBothParticipantsAndCountsOnlyIncomingMessagesAfterTheirOwnCursor() {
    var room = room(IDS.incrementAndGet());
    send(room, room.ownerIdentity());
    long firstIncoming = send(room, room.workerIdentity());
    long ownCursor = send(room, room.ownerIdentity());
    send(room, room.workerIdentity());
    reads.advanceReadPosition(room.ownerIdentity(), room.id(), new ChatReadPositionRequest(ownCursor));
    reads.advanceReadPosition(room.workerIdentity(), room.id(), new ChatReadPositionRequest(firstIncoming));

    var owner = summaries.findByRoomIdsAndMemberId(List.of(room.id()), room.owner()).get(0);
    var worker = summaries.findByRoomIdsAndMemberId(List.of(room.id()), room.worker()).get(0);

    assertThat(owner.myLastReadMessageId()).isEqualTo(ownCursor);
    assertThat(owner.otherLastReadMessageId()).isEqualTo(firstIncoming);
    assertThat(owner.unreadCount()).isEqualTo(1);
    assertThat(worker.myLastReadMessageId()).isEqualTo(firstIncoming);
    assertThat(worker.otherLastReadMessageId()).isEqualTo(ownCursor);
    assertThat(worker.unreadCount()).isEqualTo(1);
    assertThat(owner.myReadAt()).isEqualTo(worker.otherReadAt())
        .isEqualTo(positions.findByChatRoomIdAndMemberId(room.id(), room.owner()).orElseThrow().getReadAt());
    assertThat(owner.otherReadAt()).isEqualTo(worker.myReadAt());
  }

  @Test
  void batchIncludesEmptyRoomsAndTreatsMissingPositionsAsNoAcknowledgement() {
    long owner = IDS.incrementAndGet();
    var empty = room(owner);
    var ownOnly = room(owner);
    send(ownOnly, ownOnly.ownerIdentity());
    var incoming = room(owner);
    send(incoming, incoming.workerIdentity());
    send(incoming, incoming.ownerIdentity());
    send(incoming, incoming.workerIdentity());
    var ids = List.of(empty.id(), ownOnly.id(), incoming.id());

    var result = summaries.findByRoomIdsAndMemberId(ids, owner).stream()
        .collect(Collectors.toMap(ChatReadSummary::chatRoomId, Function.identity()));

    assertThat(result).hasSize(3);
    assertThat(result.get(empty.id()).unreadCount()).isZero();
    assertThat(result.get(ownOnly.id()).unreadCount()).isZero();
    assertThat(result.get(incoming.id()).unreadCount()).isEqualTo(2);
    assertThat(result.values()).allSatisfy(summary -> {
      assertThat(summary.myLastReadMessageId()).isNull();
      assertThat(summary.myReadAt()).isNull();
      assertThat(summary.otherLastReadMessageId()).isNull();
      assertThat(summary.otherReadAt()).isNull();
    });
    for (long roomId : ids) assertThat(positions.findByChatRoomIdAndMemberId(roomId, owner)).isEmpty();
  }

  @Test
  void limitsTheBatchToRequestedRoomsAndTheirParticipant() {
    var requested = room(IDS.incrementAndGet());
    room(requested.owner());
    var foreign = room(IDS.incrementAndGet());
    send(foreign, foreign.workerIdentity());

    var result = summaries.findByRoomIdsAndMemberId(List.of(requested.id(), foreign.id()), requested.owner());

    assertThat(result).extracting(ChatReadSummary::chatRoomId).containsExactly(requested.id());
    assertThat(summaries.findByRoomIdsAndMemberId(List.of(requested.id()), IDS.incrementAndGet())).isEmpty();
  }

  @Test
  void listAndDetailExposeReadPositionsFromEachViewersPerspectiveWithoutAdvancingThem() throws Exception {
    var room = room(IDS.incrementAndGet());
    long workerCursor = send(room, room.workerIdentity());
    long ownerCursor = send(room, room.ownerIdentity());
    send(room, room.workerIdentity());
    var ownerRead = reads.advanceReadPosition(room.ownerIdentity(), room.id(), new ChatReadPositionRequest(ownerCursor));
    var workerRead = reads.advanceReadPosition(room.workerIdentity(), room.id(), new ChatReadPositionRequest(workerCursor));

    for (var viewer : List.of(room.ownerIdentity(), room.workerIdentity())) {
      long mine = viewer.memberId() == room.owner() ? ownerCursor : workerCursor;
      long other = viewer.memberId() == room.owner() ? workerCursor : ownerCursor;
      String auth = token(viewer.memberId(), viewer.role());
      mvc.perform(get("/api/chat-rooms/me/" + room.id()).header("Authorization", auth))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data.chatRoomId").value(room.id()))
          .andExpect(jsonPath("$.data.myReadPosition.lastReadMessageId").value(mine))
          .andExpect(jsonPath("$.data.otherReadPosition.lastReadMessageId").value(other))
          .andExpect(jsonPath("$.data.myReadPosition.readAt").isString())
          .andExpect(jsonPath("$.data.otherReadPosition.readAt").isString())
          .andExpect(jsonPath("$.data.unreadCount").value(1));
      mvc.perform(get("/api/chat-rooms/me").header("Authorization", auth))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
          .andExpect(jsonPath("$.data.content[0].myReadPosition.lastReadMessageId").value(mine))
          .andExpect(jsonPath("$.data.content[0].otherReadPosition.lastReadMessageId").value(other))
          .andExpect(jsonPath("$.data.content[0].unreadCount").value(1));
    }
    assertThat(positions.findByChatRoomIdAndMemberId(room.id(), room.owner()).orElseThrow().getReadAt())
        .isEqualTo(ownerRead.readAt());
    assertThat(positions.findByChatRoomIdAndMemberId(room.id(), room.worker()).orElseThrow().getReadAt())
        .isEqualTo(workerRead.readAt());
  }

  @Test
  void listsEmptyAndOwnOnlyRoomsWithZeroUnreadAndNullPositionsWhileKeepingPagination() throws Exception {
    long owner = IDS.incrementAndGet();
    var empty = room(owner);
    var ownOnly = room(owner);
    send(ownOnly, ownOnly.ownerIdentity());
    var incoming = room(owner);
    send(incoming, incoming.workerIdentity());
    send(incoming, incoming.workerIdentity());
    String auth = token(owner, "OWNER");

    mvc.perform(get("/api/chat-rooms/me").header("Authorization", auth).param("size", "2"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(3))
        .andExpect(jsonPath("$.data.totalPages").value(2))
        .andExpect(jsonPath("$.data.content[0].chatRoomId").value(incoming.id()))
        .andExpect(jsonPath("$.data.content[0].unreadCount").value(2))
        .andExpect(jsonPath("$.data.content[0].myReadPosition").value(nullValue()))
        .andExpect(jsonPath("$.data.content[0].otherReadPosition").value(nullValue()))
        .andExpect(jsonPath("$.data.content[1].chatRoomId").value(ownOnly.id()))
        .andExpect(jsonPath("$.data.content[1].unreadCount").value(0));
    mvc.perform(get("/api/chat-rooms/me").header("Authorization", auth)
        .param("page", "1").param("size", "2"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].chatRoomId").value(empty.id()))
        .andExpect(jsonPath("$.data.content[0].unreadCount").value(0));
    mvc.perform(get("/api/chat-rooms/me/" + empty.id()).header("Authorization", auth))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.myReadPosition").value(nullValue()))
        .andExpect(jsonPath("$.data.otherReadPosition").value(nullValue()))
        .andExpect(jsonPath("$.data.unreadCount").value(0));
    mvc.perform(get("/api/chat-rooms/me").header("Authorization", auth).param("page", "2"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty());
    for (var room : List.of(empty, ownOnly, incoming))
      assertThat(positions.findByChatRoomIdAndMemberId(room.id(), owner)).isEmpty();
  }

  @Test
  void hidesReadStateFromOutsidersWrongRolesAndInaccessibleRooms() throws Exception {
    var room = room(IDS.incrementAndGet());
    long message = send(room, room.workerIdentity());
    reads.advanceReadPosition(room.ownerIdentity(), room.id(), new ChatReadPositionRequest(message));
    for (String auth : List.of(token(IDS.incrementAndGet(), "OWNER"), token(room.owner(), "WORKER"),
        token(room.worker(), "OWNER"))) {
      mvc.perform(get("/api/chat-rooms/me/" + room.id()).header("Authorization", auth))
          .andExpect(status().isNotFound()).andExpect(jsonPath("$.data").value(nullValue()));
      mvc.perform(get("/api/chat-rooms/me").header("Authorization", auth))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty());
    }
    var pending = room(room.owner(), false);
    var closed = room(room.owner(), false);
    rooms.close(UUID.randomUUID().toString(), closed.id());
    String ownerAuth = token(room.owner(), "OWNER");
    for (long inaccessible : List.of(pending.id(), closed.id(), Long.MAX_VALUE))
      mvc.perform(get("/api/chat-rooms/me/" + inaccessible).header("Authorization", ownerAuth))
          .andExpect(status().isNotFound()).andExpect(jsonPath("$.data").value(nullValue()));
    mvc.perform(get("/api/chat-rooms/me").header("Authorization", ownerAuth))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.length()").value(1));
    mvc.perform(get("/api/chat-rooms/me/" + room.id())).andExpect(status().isUnauthorized());
    String command = UUID.randomUUID().toString();
    assertThat(gates.transition(room.owner(), command, "prepare").state()).isEqualTo("PREPARED");
    mvc.perform(get("/api/chat-rooms/me/" + room.id()).header("Authorization", ownerAuth))
        .andExpect(status().isUnauthorized());
    assertThat(gates.transition(room.owner(), command, "commit").state()).isEqualTo("COMMITTED");
    mvc.perform(get("/api/chat-rooms/me").header("Authorization", ownerAuth))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void openApiDocumentsReadSummaryFields() throws Exception {
    mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
        .andExpect(jsonPath("$.components.schemas.ChatRoomDetailResponse.properties.myReadPosition").exists())
        .andExpect(jsonPath("$.components.schemas.ChatRoomDetailResponse.properties.otherReadPosition").exists())
        .andExpect(jsonPath("$.components.schemas.ChatRoomDetailResponse.properties.unreadCount.type").value("integer"));
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
  }

  @Test
  void listQueryCountDoesNotGrowWithTheNumberOfRoomsAndEmptyPagesSkipAggregation() {
    long owner = IDS.incrementAndGet();
    Room last = null;
    for (int i = 0; i < 6; i++) {
      last = room(owner);
      send(last, last.workerIdentity());
    }
    var identity = last.ownerIdentity();
    var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.setStatisticsEnabled(true);
    try {
      for (int size : List.of(1, 5)) {
        statistics.clear();
        var page = queries.getChatRooms(identity, 0, size);
        assertThat(page.content()).hasSize(size).allSatisfy(room -> assertThat(room.unreadCount()).isEqualTo(1));
        assertThat(page.totalElements()).isEqualTo(6);
        // 페이지 조회·전체 건수·읽음 집계의 3개 쿼리이며 반환하는 방 개수에 의존하지 않는다.
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
      }
      statistics.clear();
      assertThat(queries.getChatRooms(new AuthenticatedMember(1L, IDS.incrementAndGet(), "OWNER"), 0, 20)
          .content()).isEmpty();
      assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
      statistics.clear();
      assertThat(queries.getChatRoom(identity, last.id()).unreadCount()).isEqualTo(1);
      assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    } finally {
      statistics.setStatisticsEnabled(false);
      statistics.clear();
    }
  }

  @Test
  void committedReadsAndMessagesAreRecoveredByFreshQueriesWithoutImplicitAcknowledgement() {
    var room = room(IDS.incrementAndGet());
    long first = send(room, room.workerIdentity());
    assertThat(queries.getChatRoom(room.ownerIdentity(), room.id()).unreadCount()).isEqualTo(1);
    reads.advanceReadPosition(room.ownerIdentity(), room.id(), new ChatReadPositionRequest(first));
    assertThat(queries.getChatRoom(room.ownerIdentity(), room.id()).unreadCount()).isZero();
    send(room, room.workerIdentity());
    long ownMessage = send(room, room.ownerIdentity());

    var recovered = queries.getChatRoom(room.ownerIdentity(), room.id());
    assertThat(recovered.myReadPosition().lastReadMessageId()).isEqualTo(first);
    assertThat(recovered.otherReadPosition()).isNull();
    assertThat(recovered.unreadCount()).isEqualTo(1);
    assertThat(queries.getChatRooms(room.ownerIdentity(), 0, 20).content()).containsExactly(recovered);
    reads.advanceReadPosition(room.ownerIdentity(), room.id(), new ChatReadPositionRequest(first));
    assertThat(queries.getChatRoom(room.ownerIdentity(), room.id())).isEqualTo(recovered);
    reads.advanceReadPosition(room.ownerIdentity(), room.id(), new ChatReadPositionRequest(ownMessage));
    assertThat(queries.getChatRoom(room.ownerIdentity(), room.id()).unreadCount()).isZero();
  }

  @Test
  void readSnapshotRemainsConsistentWhileAnotherTransactionAdvancesPositionsAndSends() {
    var room = room(IDS.incrementAndGet());
    long first = send(room, room.workerIdentity());
    var transaction = new TransactionTemplate(transactionManager);
    transaction.setReadOnly(true);
    var writer = Executors.newSingleThreadExecutor();
    try {
      transaction.executeWithoutResult(status -> {
        var before = queries.getChatRoom(room.ownerIdentity(), room.id());
        assertThat(before.myReadPosition()).isNull();
        assertThat(before.otherReadPosition()).isNull();
        assertThat(before.unreadCount()).isEqualTo(1);
        CompletableFuture.runAsync(() -> {
          reads.advanceReadPosition(room.ownerIdentity(), room.id(), new ChatReadPositionRequest(first));
          reads.advanceReadPosition(room.workerIdentity(), room.id(), new ChatReadPositionRequest(first));
          send(room, room.workerIdentity());
        }, writer).orTimeout(10, TimeUnit.SECONDS).join();

        assertThat(queries.getChatRoom(room.ownerIdentity(), room.id())).isEqualTo(before);
        assertThat(queries.getChatRooms(room.ownerIdentity(), 0, 20).content()).containsExactly(before);
      });
    } finally {
      writer.shutdownNow();
    }
    var after = queries.getChatRoom(room.ownerIdentity(), room.id());
    assertThat(after.myReadPosition().lastReadMessageId()).isEqualTo(first);
    assertThat(after.otherReadPosition().lastReadMessageId()).isEqualTo(first);
    assertThat(after.unreadCount()).isEqualTo(1);
  }

  Room room(long owner) {
    return room(owner, true);
  }

  Room room(long owner, boolean isConfirmed) {
    long id = IDS.addAndGet(10);
    var request = new ChatRoomRequest(id, id + 1, owner, id + 2, "work-" + id);
    long roomId = Long.parseLong(rooms.create(UUID.randomUUID().toString(), request).chatRoomId());
    if (isConfirmed) confirmations.confirm(new MatchConfirmedEvent(UUID.randomUUID().toString(), "MatchConfirmed",
        LocalDateTime.now(), id, 1L, 1, id, Long.toString(roomId), request.workId(),
        request.jobPostId(), request.ownerMemberId(), request.workerMemberId()));
    return new Room(roomId, owner, id + 2);
  }

  long send(Room room, AuthenticatedMember sender) {
    return messages.send(sender, room.id(), new ChatMessageRequest(UUID.randomUUID().toString(), "메시지"))
        .messageId();
  }

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
