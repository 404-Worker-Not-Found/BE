package com.workernotfound.job.global.security;

import com.workernotfound.job.global.exception.BusinessException;
import com.workernotfound.job.global.exception.GlobalErrorCode;

public class InvalidAccessTokenException extends BusinessException {
    public InvalidAccessTokenException(String message) {
        super(GlobalErrorCode.INVALID_TOKEN, message);
    }

    public InvalidAccessTokenException(String message, Throwable cause) {
        this(message);
        initCause(cause);
    }
}
