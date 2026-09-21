package com.workernotfound.chat.support;

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
    registry.add("chat.internal.secret", () -> "chat-test-internal-secret");
  }
}
