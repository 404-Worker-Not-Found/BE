package com.workernotfound.chat.domain.chat.service;

import com.workernotfound.chat.domain.chat.dto.request.ChatMessageRequest;
import com.workernotfound.chat.domain.chat.dto.response.*;
import com.workernotfound.chat.domain.chat.entity.ChatMessage;
import com.workernotfound.chat.domain.chat.event.ChatMessageStored;
import com.workernotfound.chat.domain.chat.exception.ChatRoomErrorCode;
import com.workernotfound.chat.domain.chat.repository.*;
import com.workernotfound.chat.global.exception.BusinessException;
import com.workernotfound.chat.global.security.AuthenticatedMember;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatMessageService {
  private final ChatRoomRepository rooms;
  private final ChatMessageRepository messages;
  private final ApplicationEventPublisher events;

  @Transactional
  public ChatMessageResponse send(
      AuthenticatedMember member, Long roomId, ChatMessageRequest request) {
    rooms.findConfirmedForMemberForUpdate(roomId, member.memberId(), member.role())
        .orElseThrow(() -> new BusinessException(ChatRoomErrorCode.CHAT_NOT_FOUND));
    var existing = messages.findByChatRoomIdAndSenderMemberIdAndClientMessageId(
        roomId, member.memberId(), request.clientMessageId());
    if (existing.isPresent()) return replay(existing.get(), request);
    var message = ChatMessage.builder().chatRoomId(roomId).senderMemberId(member.memberId())
        .clientMessageId(request.clientMessageId()).content(request.content()).build();
    var stored = messages.save(message);
    events.publishEvent(new ChatMessageStored(roomId, stored.getId()));
    return ChatMessageResponse.from(stored);
  }

  private ChatMessageResponse replay(ChatMessage message, ChatMessageRequest request) {
    if (!message.getContent().equals(request.content()))
      throw new BusinessException(ChatRoomErrorCode.MESSAGE_KEY_CONFLICT);
    return ChatMessageResponse.from(message);
  }

  @Transactional(readOnly = true)
  public ChatMessagePageResponse getMessages(
      AuthenticatedMember member, Long roomId, Long beforeId, int size) {
    rooms.findConfirmedForMember(roomId, member.memberId(), member.role())
        .orElseThrow(() -> new BusinessException(ChatRoomErrorCode.CHAT_NOT_FOUND));
    var result = messages.findHistory(roomId, beforeId, PageRequest.of(0, size + 1));
    boolean hasNext = result.size() > size;
    var content = result.stream().limit(size).map(ChatMessageResponse::from).toList();
    Long nextBeforeId = hasNext ? content.get(content.size() - 1).messageId() : null;
    return new ChatMessagePageResponse(content, nextBeforeId, hasNext);
  }

  @Transactional(readOnly = true)
  public ChatMessageSyncResponse getNewMessages(
      AuthenticatedMember member, Long roomId, Long afterId, int size) {
    rooms.findConfirmedForMember(roomId, member.memberId(), member.role())
        .orElseThrow(() -> new BusinessException(ChatRoomErrorCode.CHAT_NOT_FOUND));
    var result = messages.findNewMessages(roomId, afterId, PageRequest.of(0, size + 1));
    var content = result.stream().limit(size).map(ChatMessageResponse::from).toList();
    Long nextAfterId = content.isEmpty() ? afterId : content.get(content.size() - 1).messageId();
    return new ChatMessageSyncResponse(content, nextAfterId, result.size() > size);
  }
}
