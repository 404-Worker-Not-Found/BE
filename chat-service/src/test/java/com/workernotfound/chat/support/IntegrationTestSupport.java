package com.workernotfound.chat.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest
public abstract class IntegrationTestSupport {
  static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

  static final org.testcontainers.containers.GenericContainer<?> REDIS =
      new org.testcontainers.containers.GenericContainer<>("redis:7.4-alpine")
          .withExposedPorts(6379);

  private static final String JWT_SECRET = "chat-test-jwt-secret-for-integration-only";

  static {
    MYSQL.start();
    REDIS.start();
  }

  @Autowired private ObjectMapper tokenMapper;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("chat.jwt.secret", () -> JWT_SECRET);
    registry.add("chat.events.initial-delay-ms", () -> "3600000");
    registry.add("chat.internal.secret", () -> "chat-test-internal-secret");
  }

  // performance_schema 잠금 정보는 애플리케이션 계정 권한 밖이므로 관찰 전용 root 연결을 쓴다.
  protected static Connection openLockObserverConnection() throws SQLException {
    return DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
  }

  protected String token(long memberId, String role) throws Exception {
    return token(memberId, role, 300);
  }

  protected String token(long memberId, String role, long seconds) throws Exception {
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String header = encoder.encodeToString(
        "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String payload = tokenMapper.writeValueAsString(Map.of("authAccountId", memberId,
        "memberId", memberId, "role", role,
        "exp", Instant.now().plusSeconds(seconds).getEpochSecond()));
    String unsigned = header + "." + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "Bearer " + unsigned + "." + encoder.encodeToString(
        mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
  }
}
