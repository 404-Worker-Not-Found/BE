package com.workernotfound.work.support;

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

  static {
    MYSQL.start();
    REDIS.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("work.jwt.secret", () -> "work-test-jwt-secret-for-integration-only");
    registry.add("work.events.initial-delay-ms", () -> "3600000");
    registry.add("work.internal.secret", () -> "work-test-internal-secret");
  }
}
