package com.workernotfound.chat.domain.chat.dto.response;

import com.workernotfound.chat.domain.chat.entity.ChatReadPosition;
import java.time.LocalDateTime;

public record ChatReadPositionResponse(Long chatRoomId, Long lastReadMessageId, LocalDateTime readAt) {
  public static ChatReadPositionResponse from(ChatReadPosition position) {
    return new ChatReadPositionResponse(position.getChatRoomId(), position.getLastReadMessageId(),
        position.getReadAt());
  }
}
