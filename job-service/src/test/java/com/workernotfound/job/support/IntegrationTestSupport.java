package com.workernotfound.job.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class IntegrationTestSupport {

	static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
		.withDatabaseName("job_service_test")
		.withUsername("test")
		.withPassword("test");

	static {
		MYSQL.start();
	}

	// 잠금 관찰만 컨테이너 관리자 연결을 사용한다. 애플리케이션 테스트 계정의 권한은 유지한다.
	protected static Connection openLockObserverConnection() throws SQLException {
		return DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
	}

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
		registry.add("job.internal.secret", () -> "test-internal-secret");
	}
}
