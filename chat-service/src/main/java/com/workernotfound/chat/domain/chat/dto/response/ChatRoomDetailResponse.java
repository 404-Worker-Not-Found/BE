package com.workernotfound.chat.domain.chat.dto.response;

import com.workernotfound.chat.domain.chat.entity.ChatRoom;
import com.workernotfound.chat.domain.chat.repository.projection.ChatReadSummary;
import java.time.LocalDateTime;

public record ChatRoomDetailResponse(
    Long chatRoomId,
    Long matchingId,
    Long jobPostId,
    Long ownerMemberId,
    Long workerMemberId,
    String workId,
    LocalDateTime confirmedAt,
    ChatParticipantReadPositionResponse myReadPosition,
    ChatParticipantReadPositionResponse otherReadPosition,
    long unreadCount) {
  public static ChatRoomDetailResponse from(ChatRoom room, ChatReadSummary summary) {
    return new ChatRoomDetailResponse(
        room.getId(), room.getMatchingId(), room.getJobPostId(),
        room.getOwnerMemberId(), room.getWorkerMemberId(),
        room.getWorkId(), room.getConfirmedAt(),
        ChatParticipantReadPositionResponse.from(summary.myLastReadMessageId(), summary.myReadAt()),
        ChatParticipantReadPositionResponse.from(summary.otherLastReadMessageId(), summary.otherReadAt()),
        summary.unreadCount());
  }
}
