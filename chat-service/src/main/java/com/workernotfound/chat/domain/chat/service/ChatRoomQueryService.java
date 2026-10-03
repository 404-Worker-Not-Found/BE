package com.workernotfound.chat.domain.chat.service;

import com.workernotfound.chat.domain.chat.dto.response.*;
import com.workernotfound.chat.domain.chat.exception.ChatRoomErrorCode;
import com.workernotfound.chat.domain.chat.repository.ChatRoomRepository;
import com.workernotfound.chat.global.exception.BusinessException;
import com.workernotfound.chat.global.security.AuthenticatedMember;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomQueryService {
  private final ChatRoomRepository rooms;

  public ChatRoomPageResponse getChatRooms(AuthenticatedMember member, int page, int size) {
    var result = rooms.findConfirmedForMember(member.memberId(), member.role(),
        PageRequest.of(page, size, Sort.by("createdAt", "id").descending()));
    return new ChatRoomPageResponse(result.getContent().stream()
        .map(ChatRoomDetailResponse::from).toList(), page, size,
        result.getTotalElements(), result.getTotalPages());
  }

  public ChatRoomDetailResponse getChatRoom(AuthenticatedMember member, Long roomId) {
    return rooms.findConfirmedForMember(roomId, member.memberId(), member.role())
        .map(ChatRoomDetailResponse::from)
        .orElseThrow(() -> new BusinessException(ChatRoomErrorCode.CHAT_NOT_FOUND));
  }
}
