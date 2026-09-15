package com.workernotfound.chat.domain.chat.dto.response;

public record ChatRoomResponse(String chatRoomId) {
  public static ChatRoomResponse from(Long chatRoomId) {
    return new ChatRoomResponse(chatRoomId.toString());
  }
}
