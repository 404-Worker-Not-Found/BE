package com.workernotfound.chat.domain.chat.service;

import com.workernotfound.chat.domain.chat.dto.response.*;
import com.workernotfound.chat.domain.chat.entity.ChatRoom;
import com.workernotfound.chat.domain.chat.exception.ChatRoomErrorCode;
import com.workernotfound.chat.domain.chat.repository.ChatRoomRepository;
import com.workernotfound.chat.domain.chat.repository.ChatReadSummaryRepository;
import com.workernotfound.chat.domain.chat.repository.projection.ChatReadSummary;
import com.workernotfound.chat.global.exception.BusinessException;
import com.workernotfound.chat.global.security.AuthenticatedMember;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomQueryService {
  private final ChatRoomRepository rooms;
  private final ChatReadSummaryRepository summaries;

  public ChatRoomPageResponse getChatRooms(AuthenticatedMember member, int page, int size) {
    var result = rooms.findConfirmedForMember(member.memberId(), member.role(),
        PageRequest.of(page, size, Sort.by("createdAt", "id").descending()));
    var readSummaries = getReadSummaries(result.getContent(), member.memberId());
    return new ChatRoomPageResponse(result.getContent().stream()
        .map(room -> ChatRoomDetailResponse.from(room, readSummaries.get(room.getId()))).toList(), page, size,
        result.getTotalElements(), result.getTotalPages());
  }

  public ChatRoomDetailResponse getChatRoom(AuthenticatedMember member, Long roomId) {
    var room = rooms.findConfirmedForMember(roomId, member.memberId(), member.role())
        .orElseThrow(() -> new BusinessException(ChatRoomErrorCode.CHAT_NOT_FOUND));
    var readSummaries = getReadSummaries(List.of(room), member.memberId());
    return ChatRoomDetailResponse.from(room, readSummaries.get(roomId));
  }

  private Map<Long, ChatReadSummary> getReadSummaries(List<ChatRoom> rooms, Long memberId) {
    if (rooms.isEmpty()) return Map.of();
    var roomIds = rooms.stream().map(ChatRoom::getId).toList();
    return summaries.findByRoomIdsAndMemberId(roomIds, memberId).stream()
        .collect(Collectors.toMap(ChatReadSummary::chatRoomId, Function.identity()));
  }
}
