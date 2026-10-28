package com.workernotfound.payment.domain.funding;

import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class FundingIncidentRepository {
  private final JdbcTemplate jdbc;
  @org.springframework.beans.factory.annotation.Value("${WORK_ATTENDANCE_TIME_ZONE:Asia/Seoul}")
  private java.time.ZoneId scheduleZone;
  public record Delivery(long id, String commandId, Long jobPostId, Long memberId, String memberRole,
      Long matchingId, Long workId, LocalDateTime occurredAt, String token) {}

  // 호출자는 주문 → 예치 잠금을 유지한다. 다른 matching slot을 뒤늦게 잠그지 않는다.
  public void record(String orderId) {
    jdbc.update("INSERT INTO payment_funding_incidents(order_id,funding_revision,job_post_id,owner_member_id,occurred_at)"
        + " SELECT o.id,n.revision,o.job_post_id,o.owner_member_id,? FROM payment_orders o"
        + " JOIN payment_funding_notifications n ON n.order_id=o.id JOIN payment_deposits d ON d.job_post_id=o.job_post_id"
        + " WHERE o.id=? AND d.funding_blocked=TRUE ON DUPLICATE KEY UPDATE id=payment_funding_incidents.id",
        LocalDateTime.now(scheduleZone).truncatedTo(java.time.temporal.ChronoUnit.MICROS),orderId);
    jdbc.update("INSERT INTO payment_settlement_histories(settlement_id,previous_status,next_status,reason,occurred_at,funding_incident_id)"
        + " SELECT s.id,s.status,'ON_HOLD','FUNDING_BLOCKED',CURRENT_TIMESTAMP(6),i.id FROM payment_settlements s"
        + " JOIN payment_deposits d ON d.id=s.deposit_id JOIN payment_funding_incidents i ON i.job_post_id=d.job_post_id"
        + " JOIN payment_funding_notifications n ON n.order_id=i.order_id AND n.revision=i.funding_revision WHERE i.order_id=?"
        + " AND s.status IN ('RESERVED','PAYOUT_FAILED')", orderId);
    jdbc.update("UPDATE payment_settlements s JOIN payment_deposits d ON d.id=s.deposit_id SET s.status='ON_HOLD',"
        + " s.hold_reason='FUNDING_BLOCKED',s.version=s.version+1 WHERE d.job_post_id=(SELECT job_post_id FROM payment_orders WHERE id=?)"
        + " AND s.status IN ('RESERVED','PAYOUT_FAILED')", orderId);
    jdbc.update("INSERT INTO payment_funding_incident_deliveries(incident_id,member_id,member_role,work_id)"
        + " SELECT i.id,i.owner_member_id,'OWNER',0 FROM payment_funding_incidents i WHERE i.order_id=?"
        + " ON DUPLICATE KEY UPDATE id=payment_funding_incident_deliveries.id", orderId);
  }

  @Transactional
  public void attach(long id) {
    Long job = jdbc.queryForObject("SELECT job_post_id FROM payment_funding_incidents WHERE id=?", Long.class, id);
    jdbc.queryForObject("SELECT id FROM payment_deposits WHERE job_post_id=? FOR UPDATE", Long.class, job);
    jdbc.update("INSERT INTO payment_funding_incident_deliveries(incident_id,member_id,member_role,matching_id,work_id)"
        + " SELECT i.id,t.worker_member_id,'WORKER',t.matching_id,t.work_id FROM payment_funding_incidents i"
        + " JOIN payment_settlement_targets t ON t.job_post_id=i.job_post_id AND t.confirmed_at<=i.occurred_at WHERE i.id=?"
        + " ON DUPLICATE KEY UPDATE id=payment_funding_incident_deliveries.id", id);
  }

  public List<Long> findUnattached() {
    return jdbc.queryForList("SELECT i.id FROM payment_funding_incidents i WHERE"
        + " (i.attach_retry_at IS NULL OR i.attach_retry_at<=CURRENT_TIMESTAMP(6)) AND EXISTS ("
        + " SELECT 1 FROM payment_settlement_targets t LEFT JOIN payment_funding_incident_deliveries r ON r.incident_id=i.id"
        + " AND r.member_id=t.worker_member_id AND r.member_role='WORKER' AND r.work_id=t.work_id"
        + " WHERE t.job_post_id=i.job_post_id AND t.confirmed_at<=i.occurred_at AND r.id IS NULL)"
        + " ORDER BY COALESCE(i.attach_retry_at,'1000-01-01 00:00:00'),i.id LIMIT 20", Long.class);
  }

  public void deferAttachment(long id) {
    jdbc.update("UPDATE payment_funding_incidents SET attach_retry_at=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 30 SECOND) WHERE id=?",id);
  }

  @Transactional
  public Delivery claim() {
    var ids=jdbc.queryForList("SELECT id FROM payment_funding_incident_deliveries WHERE delivered=FALSE"
        + " AND next_attempt_at<=CURRENT_TIMESTAMP(6) AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP(6))"
        + " ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED", Long.class);
    if(ids.isEmpty()) return null;
    long id=ids.get(0);String token=UUID.randomUUID().toString();
    jdbc.update("UPDATE payment_funding_incident_deliveries SET lease_token=?,lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 2 MINUTE) WHERE id=?", token,id);
    return jdbc.queryForObject("SELECT r.*,i.job_post_id,i.occurred_at FROM payment_funding_incident_deliveries r"
        + " JOIN payment_funding_incidents i ON i.id=r.incident_id WHERE r.id=?", (rs,row)->new Delivery(id,
        "funding-incident-"+id,rs.getLong("job_post_id"),rs.getLong("member_id"),rs.getString("member_role"),
        rs.getObject("matching_id",Long.class),rs.getLong("work_id")==0?null:rs.getLong("work_id"),
        rs.getObject("occurred_at",LocalDateTime.class),token),id);
  }

  public void finish(Delivery d, boolean success) {
    jdbc.update("UPDATE payment_funding_incident_deliveries SET delivered=?,lease_token=NULL,lease_until=NULL,"
        + " attempts=attempts+1,last_error=CASE WHEN ? THEN NULL ELSE 'DELIVERY_FAILED' END,next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 30 SECOND)"
        + " WHERE id=? AND lease_token=? AND lease_until>CURRENT_TIMESTAMP(6)",success,success,d.id(),d.token());
  }

  public Optional<Map<String,Object>> findIncident(long id) {
    return jdbc.queryForList("SELECT i.*,'REVIEW_REQUIRED' AS status,(SELECT COUNT(*) FROM payment_funding_incident_deliveries r WHERE r.incident_id=i.id) AS recipient_count,"
        + "(SELECT COUNT(*) FROM payment_funding_incident_deliveries r WHERE r.incident_id=i.id AND r.delivered=FALSE) AS pending_count"
        + " FROM payment_funding_incidents i WHERE i.id=?",id).stream().findFirst();
  }
}
