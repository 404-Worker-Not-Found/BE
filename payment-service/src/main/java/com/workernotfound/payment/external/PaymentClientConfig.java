package com.workernotfound.payment.external;

import com.workernotfound.payment.external.toss.TossProperties;
import com.workernotfound.payment.global.security.InternalApiProperties;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(TossProperties.class)
public class PaymentClientConfig {
  @Bean
  public RestClient tossRestClient(TossProperties properties) {
    return builder("https://api.tosspayments.com")
        .defaultHeaders(
            headers -> {
              if (properties.enabled()) headers.setBasicAuth(properties.secretKey(), "");
            })
        .build();
  }

  @Bean
  public RestClient fundingJobRestClient(
      @Value("${payment.job-service.base-url}") String url, InternalApiProperties properties) {
    URI uri = URI.create(url);
    boolean local =
        "http".equals(uri.getScheme())
            && java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
    if (!"https".equals(uri.getScheme()) && !local)
      throw new IllegalArgumentException("공고 서비스 URL은 HTTPS 또는 loopback HTTP여야 합니다.");
    return builder(url).defaultHeader("X-Internal-Secret", properties.secret()).build();
  }

  private RestClient.Builder builder(String url) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(3));
    factory.setReadTimeout(Duration.ofSeconds(10));
    return RestClient.builder().baseUrl(url).requestFactory(factory);
  }
}
