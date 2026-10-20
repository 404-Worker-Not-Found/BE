package com.workernotfound.chat.domain.chat.service;

import com.workernotfound.chat.domain.chat.dto.request.ChatReadPositionRequest;
import com.workernotfound.chat.domain.chat.dto.response.ChatReadPositionResponse;
import com.workernotfound.chat.domain.chat.entity.ChatReadPosition;
import com.workernotfound.chat.domain.chat.exception.ChatRoomErrorCode;
import com.workernotfound.chat.domain.chat.repository.ChatMessageRepository;
import com.workernotfound.chat.domain.chat.repository.ChatReadPositionRepository;
import com.workernotfound.chat.domain.chat.repository.ChatRoomRepository;
import com.workernotfound.chat.global.account.AccountGateService;
import com.workernotfound.chat.global.exception.BusinessException;
import com.workernotfound.chat.global.security.AuthenticatedMember;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatReadPositionService {
  private final AccountGateService accountGates;
  private final ChatRoomRepository rooms;
  private final ChatMessageRepository messages;
  private final ChatReadPositionRepository positions;

  @Transactional
  public ChatReadPositionResponse advanceReadPosition(
      AuthenticatedMember member, Long roomId, ChatReadPositionRequest request) {
    accountGates.requireActive(member.memberId());
    // 메시지 전송과 같은 방 잠금으로 최초 생성·갱신을 직렬화한다. 방 잠금 전에는 일반 조회를 하지 않는다.
    rooms.findConfirmedForMemberForUpdate(roomId, member.memberId(), member.role())
        .orElseThrow(() -> new BusinessException(ChatRoomErrorCode.CHAT_NOT_FOUND));
    if (!messages.existsByIdAndChatRoomId(request.lastReadMessageId(), roomId))
      throw new BusinessException(ChatRoomErrorCode.MESSAGE_NOT_FOUND);
    var position = positions.findByChatRoomIdAndMemberId(roomId, member.memberId())
        .orElseGet(() -> positions.save(ChatReadPosition.builder().chatRoomId(roomId)
            .memberId(member.memberId()).lastReadMessageId(request.lastReadMessageId()).build()));
    position.advanceTo(request.lastReadMessageId());
    return ChatReadPositionResponse.from(position);
  }
}
