package com.workernotfound.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.workernotfound.payment.domain.funding.*;
import com.workernotfound.payment.domain.order.dto.*;
import com.workernotfound.payment.domain.order.repository.PaymentOrderRepository;
import com.workernotfound.payment.domain.order.service.OrderApplicationService;
import com.workernotfound.payment.domain.payment.dto.request.PaymentLockRequest;
import com.workernotfound.payment.domain.payment.service.PaymentApplicationService;
import com.workernotfound.payment.domain.settlement.service.*;
import com.workernotfound.payment.external.funding.FundingIncidentClient;
import com.workernotfound.payment.external.job.FundingJobClient;
import com.workernotfound.payment.external.toss.*;
import com.workernotfound.payment.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@AutoConfigureMockMvc
class FundingIncidentIntegrationTests extends IntegrationTestSupport {
  static final AtomicLong IDS=new AtomicLong(4_000_000);
  @Autowired OrderApplicationService orders;
  @Autowired PaymentOrderRepository orderRows;
  @Autowired PaymentApplicationService payments;
  @Autowired SettlementTargetService targets;
  @Autowired SettlementReservationService settlements;
  @MockitoSpyBean FundingIncidentRepository incidents;
  @Autowired FundingIncidentRecovery recovery;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager tx;
  @Autowired MockMvc mvc;
  @MockitoBean TossGateway toss;
  @MockitoBean FundingJobClient job;
  @MockitoBean FundingIncidentClient notifications;

  OrderResponse funded() {
    var o=orders.create(UUID.randomUUID().toString(),new CreateOrderRequest(IDS.incrementAndGet(),1L,20L,new BigDecimal("10000"),"KRW"));
    String key="funding-test-"+o.jobPostId();
    when(toss.confirm(o.orderId(),key,o.amount())).thenReturn(new TossPayment(key,o.orderId(),"DONE","KRW","카드",o.amount(),o.amount(),OffsetDateTime.now()));
    orders.confirm(o.orderId(),20L,new ConfirmOrderRequest(key,o.amount()));return o;
  }
  MatchConfirmation target(OrderResponse o,LocalDateTime confirmedAt) {
    long matching=IDS.incrementAndGet(),work=IDS.incrementAndGet();
    long lock=Long.parseLong(payments.lock(UUID.randomUUID().toString(),new PaymentLockRequest(matching,o.jobPostId(),20L,30L,new BigDecimal("600"),"KRW")).paymentId());
    return new MatchConfirmation(UUID.randomUUID().toString(),matching,4L,work,lock,o.jobPostId(),20L,30L,new BigDecimal("600"),"KRW",confirmedAt);
  }
  long reserve(MatchConfirmation t) {
    targets.record(t);
    return settlements.reserve(new WorkCompletion(UUID.randomUUID().toString(),t.workId(),t.matchingId(),t.jobPostId(),20L,30L,t.confirmedAt().plusHours(1))).settlementId();
  }
  void block(OrderResponse o) {orderRows.block(orderRows.findById(o.orderId()).orElseThrow());}
  long incident(OrderResponse o) {return jdbc.queryForObject("SELECT id FROM payment_funding_incidents WHERE order_id=?",Long.class,o.orderId());}
  String state(long id) {return jdbc.queryForObject("SELECT status FROM payment_settlements WHERE id=?",String.class,id);}

  @Test void holdsOnlyUnrequestedPayoutsAndKeepsBalanceAndHistoryAtomic() {
    var o=funded();var past=LocalDateTime.of(2026,10,1,9,0);
    long reserved=reserve(target(o,past)),requested=reserve(target(o,past));
    jdbc.update("UPDATE payment_settlements SET status='PAYOUT_REQUESTED' WHERE id=?",requested);
    var before=jdbc.queryForMap("SELECT locked_amount,settlement_reserved_amount,settlement_paid_amount FROM payment_deposits WHERE job_post_id=?",o.jobPostId());
    block(o);
    assertThat(state(reserved)).isEqualTo("ON_HOLD");assertThat(state(requested)).isEqualTo("PAYOUT_REQUESTED");
    assertThat(jdbc.queryForMap("SELECT locked_amount,settlement_reserved_amount,settlement_paid_amount FROM payment_deposits WHERE job_post_id=?",o.jobPostId())).isEqualTo(before);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlement_histories WHERE funding_incident_id=?",Long.class,incident(o))).isEqualTo(1);
    new TransactionTemplate(tx).executeWithoutResult(s->incidents.record(o.orderId()));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_funding_incidents WHERE order_id=?",Long.class,o.orderId())).isEqualTo(1);
  }

  @Test void delayedConfirmationNotifiesWorkerOnceAndLostAckUsesSameCommand() {
    var o=funded();var t=target(o,LocalDateTime.of(2026,10,1,9,0));block(o);
    targets.record(t);incidents.attach(incident(o));incidents.attach(incident(o));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_funding_incident_deliveries WHERE incident_id=?",Long.class,incident(o))).isEqualTo(2);
    jdbc.update("UPDATE payment_funding_incident_deliveries SET next_attempt_at='1900-01-01' WHERE incident_id=?",incident(o));
    var first=incidents.claim();
    jdbc.update("UPDATE payment_funding_incident_deliveries SET lease_until='2000-01-01' WHERE id=?",first.id());
    var takeover=incidents.claim();assertThat(takeover.commandId()).isEqualTo(first.commandId());
    incidents.finish(first,true);
    assertThat(jdbc.queryForObject("SELECT delivered FROM payment_funding_incident_deliveries WHERE id=?",Boolean.class,first.id())).isFalse();
    incidents.finish(takeover,true);
    assertThat(jdbc.queryForObject("SELECT delivered FROM payment_funding_incident_deliveries WHERE id=?",Boolean.class,first.id())).isTrue();
  }

  @Test void recordFailureRollsBackOriginalFundingBlockAndRevision() {
    var o=funded();
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("test rollback")).when(incidents).record(o.orderId());
    assertThatThrownBy(()->block(o)).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    assertThat(orderRows.findById(o.orderId()).orElseThrow().status()).isEqualTo("DEPOSITED");
    assertThat(jdbc.queryForObject("SELECT funding_blocked FROM payment_deposits WHERE job_post_id=?",Boolean.class,o.jobPostId())).isFalse();
    assertThat(jdbc.queryForObject("SELECT revision FROM payment_funding_notifications WHERE order_id=?",Long.class,o.orderId())).isEqualTo(1);
  }

  @Test void higherFundedRevisionDoesNotResumeHistoryOrNotifyLaterConfirmations() {
    var o=funded();long settlement=reserve(target(o,LocalDateTime.of(2026,10,1,9,0)));block(o);
    long id=incident(o);
    jdbc.update("UPDATE payment_funding_notifications SET funded=TRUE,revision=revision+1 WHERE order_id=?",o.orderId());
    jdbc.update("UPDATE payment_deposits SET funding_blocked=FALSE WHERE job_post_id=?",o.jobPostId());
    targets.record(target(o,LocalDateTime.of(2099,1,1,9,0)));incidents.attach(id);
    assertThat(state(settlement)).isEqualTo("ON_HOLD");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_funding_incident_deliveries WHERE incident_id=? AND member_role='WORKER'",Long.class,id)).isEqualTo(1);
  }

  @Test void deliveryFailureRemainsPendingAndInternalReadRequiresSecret() throws Exception {
    var o=funded();block(o);
    jdbc.update("UPDATE payment_funding_incident_deliveries SET next_attempt_at='1900-01-01' WHERE incident_id=?",incident(o));
    doThrow(new IllegalStateException("test unavailable")).when(notifications).deliver(any());recovery.recover();
    verify(notifications,atLeastOnce()).deliver(any());
    assertThat(jdbc.queryForObject("SELECT delivered FROM payment_funding_incident_deliveries WHERE incident_id=?",Boolean.class,incident(o))).isFalse();
    String path="/api/payments/internal/funding-incidents/"+incident(o);
    mvc.perform(get(path)).andExpect(status().isUnauthorized());
    mvc.perform(get(path).header("X-Internal-Secret","payment-test-internal-secret")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REVIEW_REQUIRED"));
  }
}
