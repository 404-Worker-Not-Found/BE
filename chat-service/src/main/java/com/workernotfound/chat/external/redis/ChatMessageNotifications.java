package com.workernotfound.chat.external.redis;

import com.workernotfound.chat.domain.chat.event.ChatMessageStored;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChatMessageNotifications implements MessageListener {
  public static final String CHANNEL = "chat:message-notifications";
  private final StringRedisTemplate redis;
  private final SimpMessagingTemplate sockets;

  @TransactionalEventListener
  public void publish(ChatMessageStored event) {
    try { redis.convertAndSend(CHANNEL, event.chatRoomId() + ":" + event.messageId()); }
    catch (RuntimeException exception) {
      log.warn("Chat notification publish failed: {}", exception.getClass().getSimpleName());
    }
  }

  @Override
  public void onMessage(Message message, byte[] pattern) {
    try {
      String[] ids = new String(message.getBody(), StandardCharsets.US_ASCII).split(":", -1);
      if (ids.length != 2) return;
      long roomId = Long.parseLong(ids[0]);
      long messageId = Long.parseLong(ids[1]);
      if (roomId <= 0 || messageId <= 0) return;
      sockets.convertAndSend("/topic/chat-rooms/" + roomId, new ChatMessageStored(roomId, messageId));
    } catch (RuntimeException exception) {
      log.warn("Chat notification delivery failed: {}", exception.getClass().getSimpleName());
    }
  }
}
