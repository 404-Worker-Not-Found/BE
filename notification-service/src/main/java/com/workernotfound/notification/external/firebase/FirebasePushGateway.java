package com.workernotfound.notification.external.firebase;

import com.google.firebase.messaging.*;
import com.workernotfound.notification.domain.push.service.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "notification.push.enabled", havingValue = "true")
public class FirebasePushGateway implements PushGateway {
  private final FirebaseMessaging messaging;

  public PushResult send(PushDispatch dispatch) {
    if (!dispatch.expiresAt().isAfter(Instant.now()))
      return new PushResult(PushResult.Outcome.REJECTED, "EXPIRED", null);
    try {
      messaging.send(message(dispatch));
      return new PushResult(PushResult.Outcome.ACCEPTED, null, null);
    } catch (FirebaseMessagingException failure) {
      MessagingErrorCode code = failure.getMessagingErrorCode();
      PushResult.Outcome outcome = PushResult.Outcome.RETRY;
      if (code == MessagingErrorCode.UNREGISTERED) outcome = PushResult.Outcome.INVALID_TOKEN;
      if (code == MessagingErrorCode.INVALID_ARGUMENT || code == MessagingErrorCode.SENDER_ID_MISMATCH)
        outcome = PushResult.Outcome.REJECTED;
      return new PushResult(outcome, code == null ? "FCM_FAILURE" : code.name(), retryAfter(failure));
    }
  }

  Message message(PushDispatch dispatch) {
    long ttl = Math.max(0, Duration.between(Instant.now(), dispatch.expiresAt()).toMillis());
    return Message.builder().setToken(dispatch.token())
        .setNotification(Notification.builder().setTitle("Worker Not Found")
            .setBody("새 알림이 도착했습니다. 앱에서 확인해 주세요.").build())
        .putData("notificationId", Long.toString(dispatch.notificationId()))
        .putData("type", dispatch.type())
        .setAndroidConfig(AndroidConfig.builder().setTtl(ttl)
            .setNotification(AndroidNotification.builder()
                .setTag("notification-" + dispatch.notificationId()).build()).build()).build();
  }

  private Duration retryAfter(FirebaseMessagingException failure) {
    long seconds = failure.getMessagingErrorCode() == MessagingErrorCode.QUOTA_EXCEEDED ? 60 : 30;
    if (failure.getHttpResponse() != null) {
      for (var header : failure.getHttpResponse().getHeaders().entrySet()) {
        if (!"Retry-After".equalsIgnoreCase(header.getKey())) continue;
        Object value = header.getValue();
        if (value instanceof Collection<?> values) value = values.stream().findFirst().orElse(null);
        seconds = Math.max(seconds, parseRetryAfter(String.valueOf(value)));
      }
    }
    return Duration.ofSeconds(seconds);
  }

  private long parseRetryAfter(String value) {
    try { return Math.max(0, Long.parseLong(value)); }
    catch (NumberFormatException ignored) {
      try {
        return Math.max(0, Duration.between(Instant.now(),
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds());
      } catch (DateTimeException invalid) { return 0; }
    }
  }
}
