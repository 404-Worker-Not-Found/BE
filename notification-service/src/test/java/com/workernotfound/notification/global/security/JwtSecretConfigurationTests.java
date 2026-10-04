package com.workernotfound.notification.global.security;

import static org.assertj.core.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JwtSecretConfigurationTests {
  @Test
  void rejectsMissingOrWeakSecretAtStartup() {
    for (String secret : Arrays.asList(null, "", "   ", "short")) {
      var parser = new JwtTokenParser(new JwtProperties(secret));
      assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(parser, "initialize"))
          .isInstanceOf(IllegalStateException.class);
    }
  }
}
