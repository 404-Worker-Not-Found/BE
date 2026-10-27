package com.workernotfound.payment.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest
public abstract class IntegrationTestSupport {
  static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

  static {
    MYSQL.start();
    REDIS.start();
  }

  // 잠금 대기 관찰(performance_schema)에만 컨테이너 관리자 연결을 쓴다. 애플리케이션 계정 권한은 그대로 둔다.
  protected static Connection openRootConnection() throws SQLException {
    return DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    // 정산 이벤트 소비자 빈은 만들되 스케줄러가 테스트의 직접 호출과 경쟁하지 않도록 첫 실행을 미룬다.
    registry.add("payment.settlement-events.initial-delay", () -> "1h");
    registry.add("payment.work-termination.initial-delay-ms", () -> "3600000");
    registry.add("payment.jwt.secret", () -> "payment-test-jwt-secret-with-at-least-32-bytes");
    registry.add("payment.recovery.enabled", () -> "false");
    registry.add("payment.internal.secret", () -> "payment-test-internal-secret");
  }
}
