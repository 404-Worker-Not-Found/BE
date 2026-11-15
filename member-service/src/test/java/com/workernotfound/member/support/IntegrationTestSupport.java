package com.workernotfound.member.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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

	// 마이그레이션 검증처럼 빈 스키마가 필요한 테스트는 컨테이너 관리자 계정으로 별도 데이터베이스를 만든다.
	protected static DataSource createDatabase(String name) throws SQLException {
		try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
			Statement statement = connection.createStatement()) {
			statement.execute("CREATE DATABASE `" + name + "`");
		}
		return new DriverManagerDataSource(MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + name),
			"root", MYSQL.getPassword());
	}

	protected static void dropDatabase(String name) throws SQLException {
		try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
			Statement statement = connection.createStatement()) {
			statement.execute("DROP DATABASE IF EXISTS `" + name + "`");
		}
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
		registry.add("member.no-show-events.initial-delay", () -> "1h");
		registry.add("member.no-show-events.poll-interval", () -> "1h");
		// 회원 이벤트 relay도 테스트가 직접 실행한다.
		registry.add("member.outbox.initial-delay", () -> "1h");
		registry.add("member.outbox.poll-delay", () -> "1h");
	}
}
