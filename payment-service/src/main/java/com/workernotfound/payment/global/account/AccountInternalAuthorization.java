package com.workernotfound.payment.global.account;
import com.workernotfound.payment.global.exception.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class AccountInternalAuthorization {
    @Value("${payment.internal.secret:}") private String secret;
    public void verify(String supplied) {
        if (secret.isBlank() || supplied == null || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(GlobalErrorCode.UNAUTHORIZED);
        }
    }
}
