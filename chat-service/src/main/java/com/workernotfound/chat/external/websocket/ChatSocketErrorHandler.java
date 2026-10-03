package com.workernotfound.chat.external.websocket;

import java.nio.charset.StandardCharsets;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

public class ChatSocketErrorHandler extends StompSubProtocolErrorHandler {
  @Override
  public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable error) {
    var headers = StompHeaderAccessor.create(StompCommand.ERROR);
    headers.setMessage("Chat request rejected");
    headers.setContentType(MimeTypeUtils.TEXT_PLAIN);
    return MessageBuilder.createMessage("Chat request rejected".getBytes(StandardCharsets.UTF_8),
        headers.getMessageHeaders());
  }
}
