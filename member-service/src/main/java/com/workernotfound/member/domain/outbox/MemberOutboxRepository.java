package com.workernotfound.member.domain.outbox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class MemberOutboxRepository {
	private final JdbcTemplate jdbc;

	@Transactional(propagation = Propagation.MANDATORY)
	public void append(String eventId, String aggregateType, long aggregateId, String eventType, long revision,
		int version, LocalDateTime occurredAt, String payload) {
		jdbc.update("insert into member_outbox (event_id, aggregate_type, aggregate_id, event_type, revision, schema_version,"
				+ " occurred_at, payload, next_attempt_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
			eventId, aggregateType, aggregateId, eventType, revision, version, occurredAt, payload, occurredAt);
	}

	public List<Long> candidates(LocalDateTime now, int limit) {
		return jdbc.queryForList("select id from member_outbox where published_at is null and next_attempt_at <= ?"
			+ " and (lease_until is null or lease_until <= ?) order by id limit ?", Long.class, now, now, limit);
	}

	@Transactional
	public Optional<MemberOutboxMessage> claim(long id, String token, LocalDateTime now, LocalDateTime until) {
		int changed = jdbc.update("update member_outbox set lease_token = ?, lease_until = ?"
			+ " where id = ? and published_at is null and next_attempt_at <= ?"
			+ " and (lease_until is null or lease_until <= ?)", token, until, id, now, now);
		if (changed == 0) return Optional.empty();
		return jdbc.query("select * from member_outbox where id = ? and lease_token = ?", (rs, row) ->
				new MemberOutboxMessage(rs.getLong("id"), rs.getString("event_id"), rs.getString("aggregate_type"),
					rs.getLong("aggregate_id"), rs.getString("event_type"), rs.getLong("revision"),
					rs.getInt("schema_version"), rs.getTimestamp("occurred_at").toLocalDateTime(), rs.getString("payload"),
					rs.getInt("retry_count")),
			id, token).stream().findFirst();
	}

	public void published(long id, String token, LocalDateTime at) {
		jdbc.update("update member_outbox set published_at = ?, lease_token = null, lease_until = null"
			+ " where id = ? and lease_token = ? and published_at is null", at, id, token);
	}

	public void failed(long id, String token, LocalDateTime nextAttempt) {
		jdbc.update("update member_outbox set retry_count = least(retry_count + 1, 1000000), next_attempt_at = ?,"
			+ " lease_token = null, lease_until = null where id = ? and lease_token = ? and published_at is null",
			nextAttempt, id, token);
	}
}
