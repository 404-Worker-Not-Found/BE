package com.workernotfound.chat.domain.chat.service;

import com.workernotfound.chat.domain.chat.entity.ChatRoom;
import com.workernotfound.chat.domain.chat.entity.enums.ChatRoomStatus;
import com.workernotfound.chat.domain.chat.event.MatchConfirmedEvent;
import com.workernotfound.chat.domain.chat.repository.*;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatConfirmationService {
  private final ChatRoomRepository rooms;
  private final ChatRoomCommandRepository commands;
  private final ChatConfirmationEventRepository events;
  private final Validator validator;

  @Transactional
  public void confirm(MatchConfirmedEvent event) {
    validate(event);
    Long activeChatRoomId = commands.lockMatching(event.matchingId());
    ChatRoom room = rooms.findByIdForUpdate(Long.valueOf(event.chatRoomId()))
        .orElseThrow(() -> new IllegalStateException("확정 대상 채팅방이 없습니다."));
    validateSnapshot(room, event);
    recordEvent(event);
    // A canceled attempt cannot confirm itself or affect its replacement.
    if (room.getStatus() == ChatRoomStatus.CLOSED) return;
    if (!Objects.equals(activeChatRoomId, room.getId()))
      throw new IllegalStateException("확정 대상이 활성 채팅방과 일치하지 않습니다.");
    room.confirm(event.revision(), event.occurredAt());
  }

  private void validate(MatchConfirmedEvent event) {
    if (!validator.validate(event).isEmpty()
        || !"MatchConfirmed".equals(event.eventType())
        || !Integer.valueOf(1).equals(event.version())
        || !event.matchingId().equals(event.aggregateId()))
      throw new IllegalArgumentException("지원하지 않거나 유효하지 않은 채팅방 확정 이벤트입니다.");
  }

  private void validateSnapshot(ChatRoom room, MatchConfirmedEvent event) {
    if (!room.getMatchingId().equals(event.matchingId())
        || !room.getJobPostId().equals(event.jobPostId())
        || !room.getOwnerMemberId().equals(event.ownerMemberId())
        || !room.getWorkerMemberId().equals(event.workerMemberId())
        || !room.getWorkId().equals(event.workId()))
      throw new IllegalArgumentException("채팅방 확정 이벤트의 스냅샷이 일치하지 않습니다.");
  }

  private void recordEvent(MatchConfirmedEvent event) {
    String fingerprint = fingerprint(event);
    String saved = events.record(event.eventId(), fingerprint);
    if (!fingerprint.equals(saved))
      throw new IllegalArgumentException("동일 이벤트 ID에 다른 내용이 전달되었습니다.");
  }

  private String fingerprint(MatchConfirmedEvent event) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonicalPayloadV1(event).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
    }
  }

  private String canonicalPayloadV1(MatchConfirmedEvent event) {
    StringBuilder payload = new StringBuilder();
    append(payload, event.eventId());
    append(payload, event.eventType());
    append(payload, event.occurredAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    append(payload, event.aggregateId().toString());
    append(payload, event.revision().toString());
    append(payload, event.version().toString());
    append(payload, event.matchingId().toString());
    append(payload, event.chatRoomId());
    append(payload, event.workId());
    append(payload, event.jobPostId().toString());
    append(payload, event.ownerMemberId().toString());
    append(payload, event.workerMemberId().toString());
    return payload.toString();
  }

  private void append(StringBuilder payload, String value) {
    payload.append(value.length()).append(':').append(value);
  }
}
