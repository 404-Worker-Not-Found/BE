package com.workernotfound.chat.external.websocket;

import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.*;
import org.springframework.messaging.simp.config.*;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class ChatSocketConfiguration implements WebSocketMessageBrokerConfigurer {
  private final ChatSocketAuthorization authorization;
  @Value("${chat.websocket.allowed-origins:}") private String[] allowedOrigins;
  @Autowired @Lazy @Qualifier("messageBrokerTaskScheduler")
  private TaskScheduler heartbeats;

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.setErrorHandler(new ChatSocketErrorHandler());
    var endpoint = registry.addEndpoint("/api/chat-rooms/ws");
    var origins = Arrays.stream(allowedOrigins).map(String::trim)
        .filter(origin -> !origin.isEmpty()).toArray(String[]::new);
    if (origins.length > 0) endpoint.setAllowedOrigins(origins);
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.enableSimpleBroker("/topic/chat-rooms/")
        .setTaskScheduler(heartbeats).setHeartbeatValue(new long[] {10000, 10000});
    registry.setPreservePublishOrder(true);
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(authorization);
  }

  @Override
  public void configureClientOutboundChannel(ChannelRegistration registration) {
    registration.interceptors(new ChannelInterceptor() {
      @Override
      public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var headers = message.getHeaders();
        if (SimpMessageHeaderAccessor.getMessageType(headers) == SimpMessageType.MESSAGE
            && !authorization.canReceive(SimpMessageHeaderAccessor.getSessionId(headers))) return null;
        return message;
      }
    });
  }

  @Override
  public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
    registration.setMessageSizeLimit(8192).setSendBufferSizeLimit(65536)
        .setSendTimeLimit(10000).setTimeToFirstMessage(10000);
  }
}
