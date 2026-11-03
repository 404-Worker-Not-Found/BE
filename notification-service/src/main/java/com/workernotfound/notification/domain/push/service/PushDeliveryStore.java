package com.workernotfound.notification.domain.push.service;

import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PushDeliveryStore {
  private final JdbcTemplate jdbc;
  private static final int MAX_ATTEMPTS = 8;

  @Transactional
  public Optional<PushDispatch> claim() {
    var ids = jdbc.queryForList("""
        SELECT id FROM notification_push_deliveries
        WHERE (state='PENDING' AND next_attempt_at<=UTC_TIMESTAMP(6))
           OR (state='SENDING' AND lease_until<=UTC_TIMESTAMP(6))
        ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
        """, Long.class);
    if (ids.isEmpty()) return Optional.empty();
    long id = ids.get(0);
    String lease = UUID.randomUUID().toString();
    var candidate = load(id, lease);
    if (!candidate.eligible()) {
      jdbc.update("UPDATE notification_push_deliveries SET state=?,lease_token=NULL,lease_until=NULL WHERE id=?",
          candidate.dispatch().attempt() > MAX_ATTEMPTS ? "FAILED" : "CANCELED", id);
      return Optional.empty();
    }
    jdbc.update("""
        UPDATE notification_push_deliveries SET state='SENDING',attempts=attempts+1,
        lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 10 MINUTE) WHERE id=?
        """, lease, id);
    return Optional.of(candidate.dispatch());
  }

  private Candidate load(long id, String lease) {
    return jdbc.queryForObject("""
        SELECT p.*,d.token,d.active,d.generation,d.member_id AS device_member,d.member_role AS device_role,
        n.member_id,n.member_role,n.notification_type,COALESCE(g.state,'ACTIVE') AS account_state,
        (p.expires_at>UTC_TIMESTAMP(6)) AS unexpired
        FROM notification_push_deliveries p JOIN notification_device_tokens d ON d.id=p.device_token_id
        JOIN notifications n ON n.id=p.notification_id LEFT JOIN account_gates g ON g.member_id=n.member_id
        WHERE p.id=?
        """, (row, index) -> {
          int attempts = row.getInt("attempts");
          boolean eligible = row.getBoolean("active") && row.getBoolean("unexpired") && attempts < MAX_ATTEMPTS
              && row.getLong("generation") == row.getLong("token_generation")
              && row.getLong("member_id") == row.getLong("device_member")
              && row.getString("member_role").equals(row.getString("device_role"))
              && "ACTIVE".equals(row.getString("account_state"));
          var dispatch = new PushDispatch(id, lease, attempts + 1, row.getLong("notification_id"),
              row.getLong("device_token_id"), row.getLong("token_generation"), row.getString("token"),
              row.getString("notification_type"), row.getObject("expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
          return new Candidate(eligible, dispatch);
        }, id);
  }

  @Transactional
  public void complete(PushDispatch dispatch, PushResult result) {
    String state = switch (result.outcome()) {
      case ACCEPTED -> "SENT";
      case INVALID_TOKEN, REJECTED -> "FAILED";
      case RETRY -> dispatch.attempt() >= MAX_ATTEMPTS ? "FAILED" : "PENDING";
    };
    long delay = Math.min(3600L, 30L << Math.min(dispatch.attempt() - 1, 7));
    if (result.retryAfter() != null) delay = Math.max(delay, result.retryAfter().getSeconds());
    delay = Math.min(86400, delay);
    int changed = jdbc.update("""
        UPDATE notification_push_deliveries SET state=?,last_error_code=?,
        next_attempt_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),lease_token=NULL,lease_until=NULL
        WHERE id=? AND state='SENDING' AND lease_token=?
        """, state, result.code(), delay, dispatch.id(), dispatch.leaseToken());
    if (changed == 1 && result.outcome() == PushResult.Outcome.INVALID_TOKEN) deactivate(dispatch);
  }

  private void deactivate(PushDispatch dispatch) {
    jdbc.update("""
        UPDATE notification_device_tokens SET active=FALSE,token='',generation=generation+1,updated_at=UTC_TIMESTAMP(6)
        WHERE id=? AND generation=? AND active=TRUE
        """, dispatch.deviceTokenId(), dispatch.generation());
  }

  private record Candidate(boolean eligible, PushDispatch dispatch) {}
}
