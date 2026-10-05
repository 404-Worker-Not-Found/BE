package com.workernotfound.job.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

// 모집 완료 알림·결제 주문 생성 전송도 테스트에서 직접 호출한다. 실행되면 명령 상태 검증과 경쟁하므로 끈다.
// 하위 테스트가 @DynamicPropertySource로 다시 켤 수 있도록 우선순위가 낮은 인라인 속성으로 둔다.
@SpringBootTest(properties = {
	"job.recruitment-completion.dispatch-enabled=false",
	"job.recruitment-completion.dispatch-after-commit=false",
	"job.recruitment-completion.reconcile.enabled=false",
	"job.payment-order.dispatch-enabled=false",
	"job.payment-order.dispatch-after-commit=false"
})
@ActiveProfiles("test")
@Import(TestClockConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class IntegrationTestSupport {

	// 설정이 다른 테스트 클래스마다 캐시된 Spring 컨텍스트가 연결 풀(기본 10개)을 유지한다. MySQL 기본 연결 한도(151)를
	// 넘으면 나중에 뜨는 컨텍스트가 "Too many connections"로 시작하지 못하므로 한도를 넉넉히 둔다.
	static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
		.withDatabaseName("job_service_test")
		.withUsername("test")
		.withPassword("test")
		.withCommand("--max_connections=500");

	static {
		MYSQL.start();
	}

	// 잠금 관찰만 컨테이너 관리자 연결을 사용한다. 애플리케이션 테스트 계정의 권한은 유지한다.
	protected static Connection openLockObserverConnection() throws SQLException {
		return DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
	}

	// 애플리케이션을 직접 띄우는 시작 검증 테스트가 같은 컨테이너를 쓰도록 데이터소스 속성을 제공한다.
	public static String[] dataSourceProperties() {
		return new String[]{
			"spring.datasource.url=" + MYSQL.getJdbcUrl(),
			"spring.datasource.username=" + MYSQL.getUsername(),
			"spring.datasource.password=" + MYSQL.getPassword(),
			"spring.datasource.driver-class-name=" + MYSQL.getDriverClassName()
		};
	}

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
		registry.add("job.internal.secret", () -> "test-internal-secret");
		// 만료 회수는 테스트에서 직접 호출한다. 백그라운드 실행이 시간 경계 검증에 끼어들지 않게 끈다.
		registry.add("job.matching-seat-reservation.expiry-sweep-enabled", () -> "false");
	}
}
