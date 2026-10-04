package com.workernotfound.job.global.config;

import com.workernotfound.job.external.client.matching.MatchingServiceProperties;
import com.workernotfound.job.global.security.InternalApiProperties;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MatchingServiceProperties.class, InternalApiProperties.class})
public class RestClientConfig {

    @Bean
    public RestClient matchingServiceRestClient(
            MatchingServiceProperties properties,
            InternalApiProperties internalApiProperties
    ) {
        return createInternalRestClient(
                properties.baseUrl(),
                properties.connectTimeout(),
                properties.readTimeout(),
                internalApiProperties.secret()
        );
    }

    private RestClient createInternalRestClient(
            String baseUrl,
            Duration connectTimeout,
            Duration readTimeout,
            String internalSecret
    ) {
        validateSecureBaseUrl(baseUrl);
        // 응답을 기다리는 시간의 상한을 둔다. 실행권 만료 시간은 이 두 값의 합보다 길어야 한다.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(baseUrl)
                // TODO: API Gateway나 mTLS 기반 서비스 간 인증으로 대체한다.
                .defaultHeader("X-Internal-Secret", internalSecret)
                .requestFactory(requestFactory)
                .build();
    }

    private void validateSecureBaseUrl(String baseUrl) {
        URI uri = URI.create(baseUrl);
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return;
        }
        if ("http".equalsIgnoreCase(uri.getScheme()) && isLoopbackHost(uri.getHost())) {
            return;
        }
        throw new IllegalArgumentException("내부 서비스 URL은 HTTPS 또는 로컬 루프백 HTTP 주소여야 합니다.");
    }

    private boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "[::1]".equals(host);
    }
}
