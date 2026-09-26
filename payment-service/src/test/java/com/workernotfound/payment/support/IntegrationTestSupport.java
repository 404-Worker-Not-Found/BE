package com.workernotfound.payment.support;

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
