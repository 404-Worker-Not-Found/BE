package com.workernotfound.notification.external.firebase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.google.api.client.json.gson.GsonFactory;
import com.google.firebase.IncomingHttpResponse;
import com.google.firebase.messaging.*;
import com.workernotfound.notification.domain.push.service.*;
import java.time.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FirebasePushGatewayTests {
  FirebaseMessaging messaging = mock(FirebaseMessaging.class);
  FirebasePushGateway gateway = new FirebasePushGateway(messaging);

  @Test void sendsGenericVisibleNotificationWithStableDeduplicationAndNavigationData() throws Exception {
    when(messaging.send(any(Message.class))).thenReturn("projects/test/messages/1");
    assertThat(gateway.send(dispatch(Instant.now().plusSeconds(3600))).outcome()).isEqualTo(PushResult.Outcome.ACCEPTED);
    var sent = ArgumentCaptor.forClass(Message.class);
    verify(messaging).send(sent.capture());
    String json = GsonFactory.getDefaultInstance().toString(sent.getValue());
    assertThat(json).contains("test-device-token", "notificationId", "MatchConfirmed", "notification-101")
        .contains("새 알림이 도착했습니다.").doesNotContain("memberId", "account", "payment", "private_key");
  }

  @Test void onlyUnregisteredInvalidatesTokenWhilePayloadErrorsRetainIt() throws Exception {
    assertThat(failure(MessagingErrorCode.UNREGISTERED).outcome()).isEqualTo(PushResult.Outcome.INVALID_TOKEN);
    assertThat(failure(MessagingErrorCode.INVALID_ARGUMENT).outcome()).isEqualTo(PushResult.Outcome.REJECTED);
    assertThat(failure(MessagingErrorCode.SENDER_ID_MISMATCH).outcome()).isEqualTo(PushResult.Outcome.REJECTED);
    assertThat(failure(MessagingErrorCode.THIRD_PARTY_AUTH_ERROR).outcome()).isEqualTo(PushResult.Outcome.RETRY);
    assertThat(failure(MessagingErrorCode.UNAVAILABLE).outcome()).isEqualTo(PushResult.Outcome.RETRY);
  }

  @Test void honorsRetryAfterAndQuotaMinimumWithoutExposingProviderBodies() throws Exception {
    var failure = mock(FirebaseMessagingException.class);
    var response = mock(IncomingHttpResponse.class);
    when(failure.getMessagingErrorCode()).thenReturn(MessagingErrorCode.QUOTA_EXCEEDED);
    when(failure.getHttpResponse()).thenReturn(response);
    when(response.getHeaders()).thenReturn(Map.of("retry-after", "120"));
    when(messaging.send(any(Message.class))).thenThrow(failure);
    var result = gateway.send(dispatch(Instant.now().plusSeconds(3600)));
    assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(120));
    when(response.getHeaders()).thenReturn(Map.of("Retry-After", "bad-header"));
    assertThat(gateway.send(dispatch(Instant.now().plusSeconds(3600))).retryAfter()).isEqualTo(Duration.ofSeconds(60));
  }

  @Test void expiredCommandsAreNotSent() {
    assertThat(gateway.send(dispatch(Instant.now().minusSeconds(1))).code()).isEqualTo("EXPIRED");
    verifyNoInteractions(messaging);
  }

  private PushResult failure(MessagingErrorCode code) throws Exception {
    var failure = mock(FirebaseMessagingException.class);
    when(failure.getMessagingErrorCode()).thenReturn(code);
    when(messaging.send(any(Message.class))).thenThrow(failure);
    return gateway.send(dispatch(Instant.now().plusSeconds(3600)));
  }

  private PushDispatch dispatch(Instant expiration) {
    return new PushDispatch(1, "lease", 1, 101, 2, 1, "test-device-token", "MatchConfirmed", expiration);
  }
}
