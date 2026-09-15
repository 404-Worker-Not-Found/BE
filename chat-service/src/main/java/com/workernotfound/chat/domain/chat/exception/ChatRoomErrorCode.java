package com.workernotfound.chat.domain.chat.exception;

import com.workernotfound.chat.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ChatRoomErrorCode implements ErrorCode {
  CHAT_NOT_FOUND("CHAT-404-001", "채팅방을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
  COMMAND_CONFLICT("CHAT-409-001", "동일 명령 키를 다른 요청에 사용할 수 없습니다.", HttpStatus.CONFLICT),
  ACTIVE_CHAT_EXISTS("CHAT-409-002", "매칭에 활성 채팅방이 이미 존재합니다.", HttpStatus.CONFLICT);
  private final String code;
  private final String message;
  private final HttpStatus httpStatus;
}
