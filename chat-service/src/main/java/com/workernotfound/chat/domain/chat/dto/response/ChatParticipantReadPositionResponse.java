package com.workernotfound.chat.domain.chat.dto.response;

import java.time.LocalDateTime;

public record ChatParticipantReadPositionResponse(Long lastReadMessageId, LocalDateTime readAt) {
  public static ChatParticipantReadPositionResponse from(Long messageId, LocalDateTime readAt) {
    return messageId == null ? null : new ChatParticipantReadPositionResponse(messageId, readAt);
  }
}
