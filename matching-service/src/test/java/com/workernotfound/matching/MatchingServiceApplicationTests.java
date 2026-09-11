package com.workernotfound.matching;

import com.workernotfound.matching.support.IntegrationTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class MatchingServiceApplicationTests extends IntegrationTestSupport {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private Flyway flyway;

	@Test
	void databaseAndFlywayAreReady() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			assertThat(connection.isValid(1)).isTrue();
		}
		assertThat(flyway.info().pending()).isEmpty();
	}

}
