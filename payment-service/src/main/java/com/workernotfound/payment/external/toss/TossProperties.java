package com.workernotfound.payment.external.toss;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payment.toss")
public record TossProperties(boolean enabled, String secretKey) {
  public TossProperties {
    if (enabled && (secretKey == null || !secretKey.matches("test_(gsk|sk)_[A-Za-z0-9_-]+")))
      throw new IllegalArgumentException("토스 테스트 시크릿 키를 설정해야 합니다.");
  }

  @Override
  public String toString() {
    return "TossProperties[enabled=" + enabled + "]";
  }
}
