package com.workernotfound.payment.external.toss;

import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 토스페이먼츠 연동 설정.
 *
 * <p>{@code apiBaseUrl}은 기본값이 토스 API이며, 바꿀 수 있는 값은 로컬 PG 대역 서버용 loopback HTTP뿐이다. 시크릿 키를 Basic 인증으로
 * 보내므로 그 밖의 주소는 시작 단계에서 거절한다.
 */
@ConfigurationProperties(prefix = "payment.toss")
public record TossProperties(boolean enabled, String secretKey, String apiBaseUrl) {
  public static final String DEFAULT_API_BASE_URL = "https://api.tosspayments.com";
  private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

  public TossProperties {
    if (enabled && (secretKey == null || !secretKey.matches("test_(gsk|sk)_[A-Za-z0-9_-]+")))
      throw new IllegalArgumentException("토스 테스트 시크릿 키를 설정해야 합니다.");
    apiBaseUrl = apiBaseUrl == null || apiBaseUrl.isBlank() ? DEFAULT_API_BASE_URL : apiBaseUrl;
    if (!DEFAULT_API_BASE_URL.equals(apiBaseUrl) && !isLoopbackHttp(apiBaseUrl))
      throw new IllegalArgumentException("토스 API 주소는 기본 주소 또는 loopback HTTP 대역 서버여야 합니다.");
  }

  private static boolean isLoopbackHttp(String url) {
    try {
      URI uri = URI.create(url);
      return "http".equals(uri.getScheme()) && LOOPBACK_HOSTS.contains(uri.getHost());
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  @Override
  public String toString() {
    return "TossProperties[enabled=" + enabled + ", apiBaseUrl=" + apiBaseUrl + "]";
  }
}
