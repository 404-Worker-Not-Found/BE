package com.workernotfound.member.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.member.domain.review.service.TrustScorePolicy;
import com.workernotfound.member.domain.review.service.TrustScoreProperties;
import com.workernotfound.member.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// V6 이전에 받은 리뷰는 기존 평점 집계로 신뢰 점수와 TrustScoreUpdated를 한 번 채운다. 계산은 기본 설정의 Java 정책과 같다.
class TrustScoreBackfillMigrationTests extends IntegrationTestSupport {
	private final TrustScorePolicy defaultPolicy = new TrustScorePolicy(new TrustScoreProperties(new BigDecimal("3.0"), 3));
	private final ObjectMapper mapper = new ObjectMapper();
	private String database;
	private JdbcTemplate jdbc;
	private DataSource dataSource;

	@BeforeEach
	void createPreTrustScoreSchema() throws Exception {
		database = "member_v6_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		dataSource = createDatabase(database);
		jdbc = new JdbcTemplate(dataSource);
		flyway("5").migrate();
	}

	@AfterEach
	void dropSchema() throws Exception {
		dropDatabase(database);
	}

	@Test
	void backfillsTrustScoresAndEventsFromExistingReviewTotals() throws Exception {
		jdbc.update("insert into member_review_stats(member_id, review_count, rating_sum, updated_at) values"
			+ " (101, 1, 5, now(6)), (102, 3, 13, now(6)), (103, 0, 0, now(6))");

		flyway(null).migrate();

		assertThat(jdbc.queryForList("select member_id, trust_score, review_count, policy_version, revision"
			+ " from member_trust_scores order by member_id")).hasSize(2);
		assertThat(trustScore(101)).isEqualByComparingTo("62.50");
		assertThat(trustScore(102)).isEqualByComparingTo("66.67");
		Map<String, Object> event = jdbc.queryForMap("select * from member_outbox where aggregate_id = 102");
		assertThat(event).containsEntry("event_id", "trust-score-backfill-102")
			.containsEntry("aggregate_type", "MEMBER")
			.containsEntry("event_type", "TrustScoreUpdated");
		assertThat(((Number) event.get("revision")).longValue()).isEqualTo(1);
		assertThat(((Number) event.get("schema_version")).intValue()).isEqualTo(1);
		assertThat(event.get("published_at")).isNull();
		JsonNode payload = mapper.readTree((String) event.get("payload"));
		assertThat(payload.path("eventId").asText()).isEqualTo("trust-score-backfill-102");
		assertThat(payload.path("eventType").asText()).isEqualTo("TrustScoreUpdated");
		assertThat(payload.path("memberId").asLong()).isEqualTo(102);
		assertThat(payload.path("revision").asLong()).isEqualTo(1);
		assertThat(payload.path("version").asInt()).isEqualTo(1);
		assertThat(payload.path("trustScore").decimalValue()).isEqualByComparingTo("66.67");
		assertThat(payload.path("reviewCount").asLong()).isEqualTo(3);
		assertThat(payload.path("policyVersion").asText()).isEqualTo("review-rating-v1");
	}

	@Test
	void sqlBackfillMatchesJavaPolicyForEveryPossibleTotal() {
		List<long[]> totals = new ArrayList<>();
		for (long count = 1; count <= 40; count++) {
			for (long sum = count; sum <= count * 5; sum++) totals.add(new long[]{count, sum});
		}
		for (long count : new long[]{97, 499, 1001}) {
			for (long sum = count; sum <= count * 5; sum += 7) totals.add(new long[]{count, sum});
		}
		jdbc.batchUpdate("insert into member_review_stats(member_id, review_count, rating_sum, updated_at) values (?, ?, ?, now(6))",
			totals.stream().map(total -> new Object[]{total[0] * 10_000 + total[1], total[0], total[1]}).toList());

		flyway(null).migrate();

		Map<Long, BigDecimal> backfilled = new java.util.HashMap<>();
		jdbc.query("select member_id, trust_score from member_trust_scores",
			resultSet -> { backfilled.put(resultSet.getLong(1), resultSet.getBigDecimal(2)); });
		assertThat(backfilled).hasSize(totals.size());
		for (long[] total : totals) {
			assertThat(backfilled.get(total[0] * 10_000 + total[1]))
				.as("count=%d, sum=%d", total[0], total[1])
				.isEqualByComparingTo(defaultPolicy.calculate(total[0], total[1]));
		}
	}

	@Test
	void emptyReviewTotalsCreateNoScoresOrEvents() {
		flyway(null).migrate();

		assertThat(jdbc.queryForObject("select count(*) from member_trust_scores", Long.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from member_outbox", Long.class)).isZero();
	}

	private BigDecimal trustScore(long memberId) {
		return jdbc.queryForObject("select trust_score from member_trust_scores where member_id = ?", BigDecimal.class,
			memberId);
	}

	private Flyway flyway(String target) {
		var configuration = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
		if (target != null) configuration.target(target);
		return configuration.load();
	}
}
