package com.workernotfound.member.support;

import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class IntegrationTestSupport {

	static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
		.withDatabaseName("member_service_test")
		.withUsername("test")
		.withPassword("test");

	static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

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
		registry.add("member.internal.secret", () -> "test-internal-secret");
		registry.add("member.jwt.secret", () -> "test-jwt-secret-key-for-member-service-token-tests");
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
		// 리뷰 대상 근무 소비자는 테스트가 직접 poll한다. 백그라운드 실행이 테스트 순서에 끼어들지 않게 첫 실행을 미룬다.
		registry.add("member.review-events.initial-delay", () -> "1h");
		registry.add("member.review-events.poll-interval", () -> "1h");
	}
}
