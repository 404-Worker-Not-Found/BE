package com.workernotfound.chat;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.workernotfound.chat.global.security.AuthenticatedMember;
import com.workernotfound.chat.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ChatReadSummaryIntegrationTests extends IntegrationTestSupport {
  private static final AtomicLong IDS = new AtomicLong(6_000_000);
  @Autowired ChatRoomApplicationService rooms;
  @Autowired ChatConfirmationService confirmations;
  @Autowired ChatMessageService messages;
  @Autowired ChatReadPositionService reads;
  @Autowired ChatReadPositionRepository positions;
  @Autowired ChatReadSummaryRepository summaries;

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

  Room room(long owner) {
    long id = IDS.addAndGet(10);
    var request = new ChatRoomRequest(id, id + 1, owner, id + 2, "work-" + id);
    long roomId = Long.parseLong(rooms.create(UUID.randomUUID().toString(), request).chatRoomId());
    confirmations.confirm(new MatchConfirmedEvent(UUID.randomUUID().toString(), "MatchConfirmed",
        LocalDateTime.now(), id, 1L, 1, id, Long.toString(roomId), request.workId(),
        request.jobPostId(), request.ownerMemberId(), request.workerMemberId()));
    return new Room(roomId, owner, id + 2);
  }

  long send(Room room, AuthenticatedMember sender) {
    return messages.send(sender, room.id(), new ChatMessageRequest(UUID.randomUUID().toString(), "메시지"))
        .messageId();
  }
}
