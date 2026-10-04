package com.workernotfound.notification.domain.notification.exception;

import com.workernotfound.notification.global.exception.ErrorCode;
import lombok.*;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum NotificationErrorCode implements ErrorCode {
  NOT_FOUND("NOTIFICATION-404-001", "알림을 찾을 수 없습니다.", HttpStatus.NOT_FOUND);
  private final String code;
  private final String message;
  private final HttpStatus httpStatus;
}
