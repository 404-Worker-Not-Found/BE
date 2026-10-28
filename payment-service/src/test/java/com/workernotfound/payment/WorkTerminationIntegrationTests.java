package com.workernotfound.payment;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.payment.domain.payment.dto.request.PaymentLockRequest;
import com.workernotfound.payment.domain.payment.entity.PaymentDeposit;
import com.workernotfound.payment.domain.payment.repository.PaymentDepositRepository;
import com.workernotfound.payment.domain.payment.service.PaymentApplicationService;
import com.workernotfound.payment.domain.settlement.service.*;
import com.workernotfound.payment.domain.settlement.exception.SettlementTargetNotReadyException;
import com.workernotfound.payment.external.redis.WorkTerminationEventConsumer;
import com.workernotfound.payment.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class WorkTerminationIntegrationTests extends IntegrationTestSupport {
  static final AtomicLong IDS = new AtomicLong(3_000_000);
  static final LocalDateTime AT = LocalDateTime.of(2026, 10, 28, 18, 0);
  @Autowired PaymentApplicationService payments;
  @Autowired PaymentDepositRepository deposits;
  @Autowired SettlementTargetService targets;
  @Autowired SettlementReservationService settlements;
  @Autowired WorkTerminationService terminations;
  @Autowired WorkTerminationEventConsumer consumer;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired ObjectMapper mapper;
  @MockitoSpyBean com.workernotfound.payment.domain.settlement.repository.WorkTerminationRepository receipts;
  record Fixture(long job, long matching, long work, long payment) {}

  Fixture fixture(boolean confirmed) {
    long job = IDS.incrementAndGet(), matching = IDS.incrementAndGet(), work = IDS.incrementAndGet();
    deposits.saveAndFlush(PaymentDeposit.builder().jobPostId(job).ownerMemberId(20L).currency("KRW").depositedAmount(new BigDecimal("1000")).build());
    long payment = Long.parseLong(payments.lock(UUID.randomUUID().toString(), new PaymentLockRequest(matching, job, 20L, 30L, new BigDecimal("600"), "KRW")).paymentId());
    var fixture = new Fixture(job, matching, work, payment);
    if (confirmed) confirm(fixture);
    return fixture;
  }
  void confirm(Fixture fixture) {
    targets.record(new MatchConfirmation(UUID.randomUUID().toString(), fixture.matching(), 2L, fixture.work(), fixture.payment(),
        fixture.job(), 20L, 30L, new BigDecimal("600"), "KRW", AT.minusHours(9)));
  }
  WorkTerminationEvent event(Fixture fixture, String type) {
    boolean noShow = type.equals("WorkNoShow");
    return new WorkTerminationEvent(UUID.randomUUID().toString(), type, fixture.work(), noShow ? 4 : 5, 1, AT,
        fixture.work(), fixture.matching(), fixture.job(), 20L, 30L, noShow ? null : 20L, noShow ? "SYSTEM" : "OWNER", noShow ? "NO_SHOW" : "CANCELED");
  }
  BigDecimal locked(Fixture fixture) { return jdbc.queryForObject("SELECT locked_amount FROM payment_deposits WHERE job_post_id=?", BigDecimal.class, fixture.job()); }
  WorkCompletion completion(Fixture fixture) { return new WorkCompletion(UUID.randomUUID().toString(), fixture.work(), fixture.matching(), fixture.job(), 20L, 30L, AT); }
  Map<String, String> fields(WorkTerminationEvent event) throws Exception {
    return Map.of("aggregateType", "WORK", "aggregateId", Long.toString(event.workId()), "eventId", event.eventId(), "eventType", event.eventType(),
        "revision", Long.toString(event.revision()), "version", "1", "occurredAt", event.occurredAt().toString(), "payload", mapper.writeValueAsString(event));
  }

  @Test
  void historyFailureRollsBackReleasedAmountAndTerminationReceipt() {
    var fixture = fixture(true);
    org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("injected"))
        .when(receipts).releaseHistory(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    assertThatThrownBy(() -> terminations.receive(event(fixture, "WorkCanceled")))
        .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    assertThat(locked(fixture)).isEqualByComparingTo("600");
    assertThat(jdbc.queryForObject("SELECT status FROM payment_locks WHERE id=?", String.class, fixture.payment())).isEqualTo("LOCKED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_work_terminations WHERE work_id=?", Long.class, fixture.work())).isZero();
  }

  @Test
  void cancellationReleasesExactlyOnceAndBlocksLateSettlement() {
    var fixture = fixture(true);
    var event = event(fixture, "WorkCanceled");
    assertThat(terminations.receive(event)).isEqualTo("RELEASED");
    assertThat(terminations.receive(event)).isEqualTo("RELEASED");
    assertThat(terminations.receive(event(fixture, "WorkCanceled"))).isEqualTo("RELEASED");
    assertThat(locked(fixture)).isEqualByComparingTo("0");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_status_histories WHERE termination_work_id=?", Long.class, fixture.work())).isEqualTo(1);
    assertThat(settlements.reserve(completion(fixture)).outcome()).isEqualTo(SettlementReservationResult.Outcome.WORK_CONFLICT);
    assertThat(jdbc.queryForObject("SELECT refunded_amount FROM payment_deposits WHERE job_post_id=?", BigDecimal.class, fixture.job())).isEqualByComparingTo("0");
  }

  @Test
  void blockedDepositIsRecordedForReviewWithoutReleasingMoney() {
    var fixture = fixture(true);
    jdbc.update("UPDATE payment_deposits SET funding_blocked=TRUE WHERE job_post_id=?", fixture.job());
    assertThat(terminations.receive(event(fixture, "WorkNoShow"))).isEqualTo("REVIEW_REQUIRED");
    assertThat(locked(fixture)).isEqualByComparingTo("600");
    assertThat(jdbc.queryForObject("SELECT reason FROM payment_work_terminations WHERE work_id=?", String.class, fixture.work())).isEqualTo("FUNDING_BLOCKED");
  }

  @Test
  void transferredSettlementIsHeldRatherThanReleased() {
    var fixture = fixture(true);
    settlements.reserve(completion(fixture));
    assertThat(terminations.receive(event(fixture, "WorkNoShow"))).isEqualTo("REVIEW_REQUIRED");
    assertThat(jdbc.queryForObject("SELECT status FROM payment_settlements WHERE work_id=?", String.class, fixture.work())).isEqualTo("ON_HOLD");
    assertThat(jdbc.queryForObject("SELECT settlement_reserved_amount FROM payment_deposits WHERE job_post_id=?", BigDecimal.class, fixture.job())).isEqualByComparingTo("600");
  }

  @Test
  void workBeforeConfirmationIsRetriedAndStreamReplayDoesNotDoubleRelease() throws Exception {
    var fixture = fixture(false);
    var event = event(fixture, "WorkNoShow");
    assertThatThrownBy(() -> terminations.receive(event)).isInstanceOf(SettlementTargetNotReadyException.class);
    redis.opsForStream().add(StreamRecords.newRecord().in("work:domain-events").ofMap(fields(event)));
    consumer.poll();
    assertThat(locked(fixture)).isEqualByComparingTo("600");
    confirm(fixture);
    consumer.poll(); consumer.poll();
    assertThat(locked(fixture)).isEqualByComparingTo("0");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_work_terminations WHERE work_id=?", Long.class, fixture.work())).isEqualTo(1);
  }

  @Test
  void changedEventIdentityAndWrongWorkDoNotReleaseAnotherLock() {
    var fixture = fixture(true);
    var event = event(fixture, "WorkCanceled");
    var wrong = new WorkTerminationEvent(event.eventId(), event.eventType(), event.workId() + 100, event.revision(), 1, AT,
        event.workId() + 100, event.matchingId(), event.jobPostId(), 20L, 30L, 20L, "OWNER", "CANCELED");
    assertThat(terminations.receive(wrong)).isEqualTo("REJECTED_WORK_MISMATCH");
    assertThatThrownBy(() -> terminations.receive(event)).isInstanceOf(IllegalArgumentException.class);
    assertThat(locked(fixture)).isEqualByComparingTo("600");
    assertThat(terminations.receive(event(fixture, "WorkCanceled"))).isEqualTo("RELEASED");
  }

  @Test
  void simultaneousTerminationAndCompletionCannotSpendSameAmountTwice() throws Exception {
    var fixture = fixture(true);
    var executor = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      var terminate = executor.submit(() -> { start.await(); return terminations.receive(event(fixture, "WorkNoShow")); });
      var complete = executor.submit(() -> { start.await(); return settlements.reserve(completion(fixture)); });
      start.countDown(); terminate.get(10, TimeUnit.SECONDS); complete.get(10, TimeUnit.SECONDS);
      var row = jdbc.queryForMap("SELECT locked_amount,settlement_reserved_amount,refunded_amount FROM payment_deposits WHERE job_post_id=?", fixture.job());
      assertThat((BigDecimal) row.get("locked_amount")).isEqualByComparingTo("0");
      assertThat((BigDecimal) row.get("settlement_reserved_amount")).isIn(new BigDecimal("0.00"), new BigDecimal("600.00"));
      assertThat((BigDecimal) row.get("refunded_amount")).isEqualByComparingTo("0");
    } finally { executor.shutdownNow(); }
  }
}
