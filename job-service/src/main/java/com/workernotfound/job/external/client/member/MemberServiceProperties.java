package com.workernotfound.job.external.client.member;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "job.member-service")
public record MemberServiceProperties(String baseUrl, Duration connectTimeout, Duration callTimeout) {
    public MemberServiceProperties {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("회원 서비스 URL은 필수입니다.");
        URI uri = URI.create(baseUrl);
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean local = "http".equalsIgnoreCase(uri.getScheme())
                && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost()));
        if ((!https && !local) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("회원 서비스 URL은 HTTPS 또는 loopback HTTP 주소여야 합니다.");
        }
        if (connectTimeout == null || callTimeout == null || connectTimeout.toMillis() < 1
                || callTimeout.toMillis() < 1 || callTimeout.compareTo(Duration.ofMinutes(1)) > 0
                || connectTimeout.compareTo(callTimeout) > 0) {
            throw new IllegalArgumentException("회원 서비스 연결·전체 제한은 1ms 이상 1분 이하이며 연결 제한은 전체 제한 이하여야 합니다.");
        }
    }
}
