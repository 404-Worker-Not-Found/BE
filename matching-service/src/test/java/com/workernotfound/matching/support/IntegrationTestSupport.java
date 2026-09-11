package com.workernotfound.matching.support;

import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class IntegrationTestSupport {

	static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
		.withDatabaseName("matching_service_test")
		.withUsername("test")
		.withPassword("test");

	static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4"))
		.withExposedPorts(6379);

	static {
		MYSQL.start();
		REDIS.start();
	}

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
		registry.add("matching.application-queue.recovery-interval", () -> "1h");
		registry.add("matching.application-queue.recovery-initial-delay", () -> "1h");
		registry.add("matching.jwt.secret", () -> "matching-test-jwt-secret-at-least-32-characters");
		registry.add("matching.member-service.internal-secret", () -> "matching-test-internal-secret");
		registry.add("matching.job-service.internal-secret", () -> "matching-test-internal-secret");
	}
}
