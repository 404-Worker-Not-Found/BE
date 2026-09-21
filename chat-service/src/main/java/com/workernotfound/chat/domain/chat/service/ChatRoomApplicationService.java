package com.workernotfound.chat.domain.chat.service;

import com.workernotfound.chat.domain.chat.dto.request.ChatRoomRequest;
import com.workernotfound.chat.domain.chat.dto.response.ChatRoomResponse;
import com.workernotfound.chat.domain.chat.entity.ChatRoom;
import com.workernotfound.chat.domain.chat.exception.ChatRoomErrorCode;
import com.workernotfound.chat.domain.chat.repository.*;
import com.workernotfound.chat.global.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatRoomApplicationService {
  private final ChatRoomRepository rooms;
  private final ChatRoomCommandRepository commands;

  @Transactional
  public ChatRoomResponse create(String key, ChatRoomRequest request) {
    var command = lockCommand(key, "CREATE:" + request.toString());
    if (command.chatRoomId() != null) return ChatRoomResponse.from(command.chatRoomId());
    if (commands.lockMatching(request.matchingId()) != null) {
      throw new BusinessException(ChatRoomErrorCode.ACTIVE_CHAT_EXISTS);
    }
    ChatRoom room =
        rooms.saveAndFlush(
            ChatRoom.builder()
                .matchingId(request.matchingId())
                .jobPostId(request.jobPostId())
                .ownerMemberId(request.ownerMemberId())
                .workerMemberId(request.workerMemberId())
                .workId(request.workId())
                .build());
    commands.activate(request.matchingId(), room.getId());
    commands.history(room.getId(), null, "OPEN", key);
    commands.complete(key, room.getId());
    return ChatRoomResponse.from(room.getId());
  }

  @Transactional
  public void close(String key, Long chatRoomId) {
    var command = lockCommand(key, "CLOSE:" + chatRoomId);
    if (command.chatRoomId() != null) return;
    Long matchingId =
        rooms
            .findMatchingIdById(chatRoomId)
            .orElseThrow(() -> new BusinessException(ChatRoomErrorCode.CHAT_NOT_FOUND));
    commands.lockMatching(matchingId);
    ChatRoom room =
        rooms
            .findByIdForUpdate(chatRoomId)
            .orElseThrow(() -> new BusinessException(ChatRoomErrorCode.CHAT_NOT_FOUND));
    if (room.close()) {
      commands.history(chatRoomId, "OPEN", "CLOSED", key);
      commands.release(room.getMatchingId(), chatRoomId);
    }
    commands.complete(key, chatRoomId);
  }

  private ChatRoomCommandRepository.Command lockCommand(String key, String payload) {
    String fingerprint = fingerprint(payload);
    var command = commands.lockCommand(key, fingerprint);
    if (!command.fingerprint().equals(fingerprint))
      throw new BusinessException(ChatRoomErrorCode.COMMAND_CONFLICT);
    return command;
  }

  private String fingerprint(String payload) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
    }
  }
}
