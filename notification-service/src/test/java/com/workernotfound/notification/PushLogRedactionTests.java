package com.workernotfound.notification;

import com.workernotfound.notification.domain.push.dto.request.DeviceTokenRequest;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.workernotfound.notification.domain.push.service.*;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class PushLogRedactionTests {
  @Test void providerExceptionsAndObjectStringsNeverExposeToken(CapturedOutput output) {
    String secret = "sensitive-registration-token";
    var dispatch = new PushDispatch(1, "lease", 1, 2, 3, 1, secret, "MatchConfirmed", Instant.now().plusSeconds(300));
    var store = mock(PushDeliveryStore.class);
    when(store.claim()).thenReturn(Optional.of(dispatch), Optional.empty());
    new PushDispatcher(store, message -> { throw new IllegalStateException(secret); }).dispatch();
    assertThat(output.getAll()).contains("푸시 발송 실패").doesNotContain(secret);
    assertThat(dispatch.toString()).doesNotContain(secret);
    assertThat(new DeviceTokenRequest(secret, "ANDROID").toString()).doesNotContain(secret);
  }
}
