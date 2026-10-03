package com.workernotfound.chat.global.security;

import com.workernotfound.chat.global.exception.BusinessException;
import com.workernotfound.chat.global.exception.GlobalErrorCode;

public class InvalidAccessTokenException extends BusinessException {
  public InvalidAccessTokenException(String message) {
    super(GlobalErrorCode.INVALID_TOKEN, message);
  }

  public InvalidAccessTokenException(String message, Throwable cause) {
    this(message);
    initCause(cause);
  }
}
