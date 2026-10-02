package com.workernotfound.chat.domain.chat.dto.response;

import com.workernotfound.chat.domain.chat.entity.ChatRoom;
import java.time.LocalDateTime;

public record ChatRoomDetailResponse(
    Long chatRoomId,
    Long matchingId,
    Long jobPostId,
    Long ownerMemberId,
    Long workerMemberId,
    String workId,
    LocalDateTime confirmedAt) {
  public static ChatRoomDetailResponse from(ChatRoom room) {
    return new ChatRoomDetailResponse(
        room.getId(), room.getMatchingId(), room.getJobPostId(),
        room.getOwnerMemberId(), room.getWorkerMemberId(),
        room.getWorkId(), room.getConfirmedAt());
  }
}
