package com.workernotfound.chat.domain.chat.dto.response;

import com.workernotfound.chat.domain.chat.entity.ChatMessage;
import java.time.LocalDateTime;

public record ChatMessageResponse(
    Long messageId, Long chatRoomId, Long senderMemberId,
    String clientMessageId, String content, LocalDateTime createdAt) {
  public static ChatMessageResponse from(ChatMessage message) {
    return new ChatMessageResponse(message.getId(), message.getChatRoomId(),
        message.getSenderMemberId(), message.getClientMessageId(),
        message.getContent(), message.getCreatedAt());
  }
}
