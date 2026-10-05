package com.workernotfound.chat.external.websocket;

import com.workernotfound.chat.domain.chat.service.ChatRoomQueryService;
import com.workernotfound.chat.global.account.AccountGateService;
import com.workernotfound.chat.global.security.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
@RequiredArgsConstructor
public class ChatSocketAuthorization implements ChannelInterceptor {
  private static final Pattern DESTINATION = Pattern.compile("/topic/chat-rooms/([1-9][0-9]{0,18})");
  private final AccountGateService accountGates;
  private final JwtTokenParser tokens;
  private final ChatRoomQueryService rooms;
  private final Map<String, AuthenticatedChatSession> sessions = new ConcurrentHashMap<>();

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    var headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (headers == null) throw denied();
    var command = headers.getCommand();
    if (command == StompCommand.DISCONNECT) return message;
    if (command != StompCommand.CONNECT && headers.isMutable()) headers.removeNativeHeader("Authorization");
    if (command == StompCommand.CONNECT) return connect(message, headers);
    var session = sessions.get(headers.getSessionId());
    if (session == null || session.isExpired() || !accountGates.isActive(session.member().memberId())) throw denied();
    if (command == StompCommand.SUBSCRIBE) authorizeSubscription(headers, session);
    else if (command != StompCommand.UNSUBSCRIBE && command != null) throw denied();
    return message;
  }

  private Message<?> connect(Message<?> message, StompHeaderAccessor headers) {
    var values = headers.getNativeHeader("Authorization");
    headers.removeNativeHeader("Authorization");
    if (values == null || values.size() != 1 || !values.get(0).startsWith("Bearer ")
        || headers.getSessionId() == null || sessions.containsKey(headers.getSessionId())) throw denied();
    AuthenticatedChatSession session;
    try { session = tokens.parseSession(values.get(0).substring(7)); }
    catch (InvalidAccessTokenException exception) { throw denied(); }
    if (!accountGates.isActive(session.member().memberId())) throw denied();
    headers.setUser(session);
    sessions.put(headers.getSessionId(), session);
    return message;
  }

  private void authorizeSubscription(StompHeaderAccessor headers, AuthenticatedChatSession session) {
    String destination = headers.getDestination();
    if (destination == null) throw denied();
    var matcher = DESTINATION.matcher(destination);
    if (!matcher.matches()) throw denied();
    long roomId;
    try { roomId = Long.parseLong(matcher.group(1)); }
    catch (NumberFormatException exception) { throw denied(); }
    rooms.getChatRoom(session.member(), roomId);
  }

  public boolean canReceive(String sessionId) {
    var session = sessionId == null ? null : sessions.get(sessionId);
    return session != null && !session.isExpired() && accountGates.isActive(session.member().memberId());
  }

  @EventListener
  public void disconnected(SessionDisconnectEvent event) { sessions.remove(event.getSessionId()); }

  private AccessDeniedException denied() { return new AccessDeniedException("채팅 연결 권한이 없습니다."); }
}
