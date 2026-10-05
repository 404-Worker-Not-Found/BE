package com.workernotfound.work.global.account;

import com.workernotfound.work.global.exception.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AccountInternalAuthorization {
    @Value("${work.internal.secret:}") private String secret;
    public void verify(String supplied) {
        if (secret.isBlank() || supplied == null || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(GlobalErrorCode.UNAUTHORIZED);
        }
    }
}
