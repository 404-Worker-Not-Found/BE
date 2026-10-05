package com.workernotfound.payment.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest
public abstract class IntegrationTestSupport {
  static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

  static {
    MYSQL.start();
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
    registry.add("payment.jwt.secret", () -> "payment-test-jwt-secret-with-at-least-32-bytes");
    registry.add("payment.recovery.enabled", () -> "false");
    registry.add("payment.internal.secret", () -> "payment-test-internal-secret");
  }
}
