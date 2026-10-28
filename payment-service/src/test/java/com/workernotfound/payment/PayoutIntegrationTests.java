package com.workernotfound.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.workernotfound.payment.domain.payment.dto.request.PaymentLockRequest;
import com.workernotfound.payment.domain.payment.entity.PaymentDeposit;
import com.workernotfound.payment.domain.payment.repository.PaymentDepositRepository;
import com.workernotfound.payment.domain.payment.service.PaymentApplicationService;
import com.workernotfound.payment.domain.payout.repository.PayoutRepository;
import com.workernotfound.payment.domain.payout.repository.PayoutRepository.Claim;
import com.workernotfound.payment.domain.payout.service.*;
import com.workernotfound.payment.domain.settlement.repository.SettlementHistoryRepository;
import com.workernotfound.payment.domain.settlement.service.*;
import com.workernotfound.payment.external.payout.PayoutGateway;
import com.workernotfound.payment.external.payout.PayoutGateway.*;
import com.workernotfound.payment.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@TestPropertySource(properties = {"payment.payout.enabled=true", "payment.payout.initial-delay=1h", "WORK_ATTENDANCE_TIME_ZONE=UTC", "payment.payout.lease-duration=2m"})
class PayoutIntegrationTests extends IntegrationTestSupport {
  static final AtomicLong IDS = new AtomicLong(2_000_000);
  static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
  static final LocalDateTime SCHEDULE_NOW = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
  @Autowired PayoutExecutionService execution;
  @Autowired PayoutTransactionService transactions;
  @Autowired PayoutRepository commands;
  @Autowired PaymentApplicationService payments;
  @Autowired PaymentDepositRepository deposits;
  @Autowired SettlementTargetService targets;
  @Autowired SettlementReservationService settlements;
  @Autowired JdbcTemplate jdbc;
  @Autowired jakarta.persistence.EntityManager entityManager;
  @Autowired MockMvc mvc;
  @MockitoBean PayoutGateway gateway;
  @MockitoBean Clock payoutClock;
  @MockitoSpyBean SettlementHistoryRepository histories;
  final List<Long> created = new ArrayList<>();
  Instant testNow = NOW;

  record Fixture(long job, long id) {}

  @BeforeEach void setup() {
    when(payoutClock.instant()).thenAnswer(call -> testNow);
    when(gateway.resolveRecipient(30)).thenReturn("test-seller-30");
  }

  // 기존 공용 fixture를 지우는 테스트가 FK 때문에 실패하지 않도록 이 클래스의 지급 명령만 제거한다.
  @AfterEach void cleanup() {
    for (long id : created) jdbc.update("DELETE FROM payment_payout_commands WHERE settlement_id=?", id);
  }

  Fixture fixture(LocalDateTime payableAt) { return fixture(payableAt, 30L); }

  Fixture fixture(LocalDateTime payableAt, long workerId) {
    long job = IDS.incrementAndGet(), matching = IDS.incrementAndGet(), work = IDS.incrementAndGet();
    deposits.saveAndFlush(PaymentDeposit.builder().jobPostId(job).ownerMemberId(20L).currency("KRW")
        .depositedAmount(new BigDecimal("1000")).build());
    long payment = Long.parseLong(payments.lock(UUID.randomUUID().toString(), new PaymentLockRequest(
        matching, job, 20L, workerId, new BigDecimal("600"), "KRW")).paymentId());
    LocalDateTime completed = payableAt.minusHours(24);
    targets.record(new MatchConfirmation(UUID.randomUUID().toString(), matching, 4L, work, payment,
        job, 20L, workerId, new BigDecimal("600"), "KRW", completed.minusHours(1)));
    long id = settlements.reserve(new WorkCompletion(UUID.randomUUID().toString(), work, matching, job,
        20L, workerId, completed)).settlementId();
    created.add(id);
    return new Fixture(job, id);
  }

  Fixture due() { return fixture(SCHEDULE_NOW); }
  Claim claim(Fixture f) { return transactions.claim(commands.findReferences(f.id()).orElseThrow(), "test-seller-30"); }
  Request request(Fixture f) {
    return jdbc.queryForObject("SELECT * FROM payment_payout_commands WHERE settlement_id=?", (rs,row) -> new Request(
        rs.getString("reference"), rs.getString("recipient"), rs.getBigDecimal("amount"), rs.getString("currency")), f.id());
  }
  Result result(Request request, Status status) {
    return new Result(request.reference(), request.recipient(), request.amount(), request.currency(), "provider-" + request.reference(),
        status, status == Status.SUCCEEDED ? testNow.atOffset(ZoneOffset.UTC) : null);
  }
  String settlementStatus(Fixture f) {
    return jdbc.queryForObject("SELECT status FROM payment_settlements WHERE id=?", String.class, f.id());
  }
  String commandStatus(Fixture f) {
    return jdbc.queryForObject("SELECT status FROM payment_payout_commands WHERE settlement_id=?", String.class, f.id());
  }
  BigDecimal balance(Fixture f, String column) {
    return jdbc.queryForObject("SELECT " + column + " FROM payment_deposits WHERE job_post_id=?", BigDecimal.class, f.job());
  }
  void retryDue(Fixture f) {
    jdbc.update("UPDATE payment_payout_commands SET lease_until='2000-01-01', next_attempt_at='2000-01-01' WHERE settlement_id=?", f.id());
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void unreadyFirstSettlementDoesNotStarveLaterBatchesEvenAfterItsRetryBecomesDue() {
    jdbc.update("UPDATE payment_settlements SET payable_at=? WHERE status='RESERVED'", SCHEDULE_NOW.plusYears(1));
    var blocked = due();
    var healthy = fixture(SCHEDULE_NOW, 31L);
    when(gateway.resolveRecipient(30)).thenThrow(new IllegalStateException("recipient unavailable"));
    when(gateway.resolveRecipient(31)).thenReturn("test-seller-31");
    when(gateway.request(any())).thenAnswer(call -> result(call.getArgument(0), Status.SUCCEEDED));
    execution.executeDue(1);
    assertThat(settlementStatus(blocked)).isEqualTo("RESERVED");
    assertThat(commands.hasCommand(blocked.id())).isFalse();
    testNow = NOW.plusSeconds(600);
    execution.executeDue(1);
    entityManager.flush();
    assertThat(settlementStatus(healthy)).isEqualTo("PAID");
    assertThat(balance(blocked, "settlement_reserved_amount")).isEqualByComparingTo("600");
    verify(gateway, times(1)).request(any());
  }

  @Test void preflightCooldownSurvivesRetriesAndCanRecoverWithARecipient() {
    var f = due();
    when(gateway.resolveRecipient(30)).thenReturn(null);
    execution.execute(f.id());
    assertThat(commands.hasCommand(f.id())).isFalse();
    assertThat(jdbc.queryForObject("SELECT payout_next_check_at FROM payment_settlements WHERE id=?",
        LocalDateTime.class, f.id())).isEqualTo(LocalDateTime.ofInstant(NOW.plusSeconds(300), ZoneOffset.UTC));
    when(gateway.resolveRecipient(30)).thenReturn("test-seller-30");
    execution.execute(f.id());
    verify(gateway, times(1)).resolveRecipient(30);
    testNow = NOW.plusSeconds(300);
    when(gateway.request(any())).thenAnswer(call -> result(call.getArgument(0), Status.SUCCEEDED));
    execution.execute(f.id());
    assertThat(settlementStatus(f)).isEqualTo("PAID");
    verify(gateway, times(1)).request(any());
  }

  @Test void invalidOriginalLockIsDeferredWithoutReleasingMoney() {
    var f = due();
    jdbc.update("UPDATE payment_matching_slots SET active_payment_id=NULL WHERE matching_id=(SELECT matching_id FROM payment_settlements WHERE id=?)", f.id());
    execution.execute(f.id());
    assertThat(settlementStatus(f)).isEqualTo("RESERVED");
    assertThat(jdbc.queryForObject("SELECT payout_check_failures FROM payment_settlements WHERE id=?", Integer.class, f.id())).isEqualTo(1);
    assertThat(balance(f, "settlement_reserved_amount")).isEqualByComparingTo("600");
    assertThat(commands.hasCommand(f.id())).isFalse();
    verify(gateway, never()).request(any());
  }

  @Test void preparationDeferralCannotChangeAnAlreadyRequestedPayoutAndLeaseIsConfigured() {
    var f = due();
    Claim active = claim(f);
    assertThat(jdbc.queryForObject("SELECT lease_until FROM payment_payout_commands WHERE settlement_id=?",
        LocalDateTime.class, f.id())).isEqualTo(LocalDateTime.ofInstant(NOW.plusSeconds(120), ZoneOffset.UTC));
    commands.deferPreparation(f.id(), LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    assertThat(jdbc.queryForObject("SELECT payout_check_failures FROM payment_settlements WHERE id=?", Integer.class, f.id())).isZero();
    assertThat(settlementStatus(f)).isEqualTo("PAYOUT_REQUESTED");
    transactions.settle(active, result(active.request(), Status.SUCCEEDED));
    assertThat(settlementStatus(f)).isEqualTo("PAID");
  }

  @Test void paysExactlyOnceAtScheduleZoneBoundaryAndRecordsProviderTime() {
    var future = fixture(SCHEDULE_NOW.plusNanos(1000));
    assertThat(claim(future)).isNull();
    var f = due();
    when(gateway.request(any())).thenAnswer(call -> result(call.getArgument(0), Status.SUCCEEDED));
    assertThat(commands.findDue(SCHEDULE_NOW, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), 100))
        .contains(f.id()).doesNotContain(future.id());
    execution.execute(f.id());
    execution.execute(f.id());
    assertThat(settlementStatus(f)).isEqualTo("PAID");
    assertThat(settlementStatus(future)).isEqualTo("RESERVED");
    assertThat(balance(f, "settlement_reserved_amount")).isEqualByComparingTo("0");
    assertThat(balance(f, "settlement_paid_amount")).isEqualByComparingTo("600");
    assertThat(jdbc.queryForObject("SELECT paid_at FROM payment_settlements WHERE id=?", LocalDateTime.class, f.id()))
        .isEqualTo(SCHEDULE_NOW);
    verify(gateway, times(1)).request(any());
    verify(gateway, never()).lookup(any());
  }

  @Test void responseLostThenLookupSuccessNeverSendsAgainEvenAfterFundingBlock() {
    var f = due();
    when(gateway.request(any())).thenThrow(new IllegalStateException("test response loss"));
    execution.execute(f.id());
    var original = request(f);
    assertThat(settlementStatus(f)).isEqualTo("PAYOUT_REQUESTED");
    jdbc.update("UPDATE payment_deposits SET funding_blocked=TRUE WHERE job_post_id=?", f.job());
    when(gateway.lookup(original.reference())).thenReturn(Optional.of(result(original, Status.SUCCEEDED)));
    retryDue(f);
    execution.execute(f.id());
    assertThat(settlementStatus(f)).isEqualTo("PAID");
    verify(gateway, times(1)).request(original);
    verify(gateway, times(1)).resolveRecipient(30);
    verify(gateway).lookup(original.reference());
  }

  @Test void lookupAbsenceAfterCrashRequiresReviewWithoutResendOrReleasingMoney() {
    var f = due();
    Claim first = claim(f); // 영속화 직후 HTTP 전 프로세스가 종료된 상황
    when(gateway.lookup(first.request().reference())).thenReturn(Optional.empty());
    retryDue(f);
    execution.execute(f.id());
    assertThat(commandStatus(f)).isEqualTo("REVIEW_REQUIRED");
    assertThat(settlementStatus(f)).isEqualTo("PAYOUT_REQUESTED");
    assertThat(balance(f, "settlement_reserved_amount")).isEqualByComparingTo("600");
    verify(gateway, never()).request(any());
  }

  @Test void pendingAndTransportErrorsKeepOriginalReferenceAndReservedAmount() {
    var f = due();
    when(gateway.request(any())).thenAnswer(call -> result(call.getArgument(0), Status.PENDING));
    execution.execute(f.id());
    Request original = request(f);
    retryDue(f);
    when(gateway.lookup(original.reference())).thenThrow(new IllegalStateException("unavailable"));
    execution.execute(f.id());
    assertThat(request(f)).isEqualTo(original);
    assertThat(commandStatus(f)).isEqualTo("AWAITING_RESULT");
    assertThat(balance(f, "settlement_reserved_amount")).isEqualByComparingTo("600");
    verify(gateway, times(1)).request(original);
  }

  @Test void providerIdentityCannotChangeAfterPendingAcceptance() {
    var f = due();
    Claim first = claim(f);
    transactions.settle(first, result(first.request(), Status.PENDING));
    retryDue(f);
    Claim next = claim(f);
    Request r = next.request();
    transactions.settle(next, new Result(r.reference(), r.recipient(), r.amount(), r.currency(),
        "different-provider-id", Status.SUCCEEDED, NOW.atOffset(ZoneOffset.UTC)));
    assertThat(commandStatus(f)).isEqualTo("REVIEW_REQUIRED");
    assertThat(settlementStatus(f)).isEqualTo("PAYOUT_REQUESTED");
    assertThat(balance(f, "settlement_reserved_amount")).isEqualByComparingTo("600");
  }

  @Test void failedPayoutKeepsReservedAmountAndDoesNotAutomaticallyRetry() {
    var f = due();
    when(gateway.request(any())).thenAnswer(call -> result(call.getArgument(0), Status.FAILED));
    execution.execute(f.id());
    execution.execute(f.id());
    assertThat(settlementStatus(f)).isEqualTo("PAYOUT_FAILED");
    assertThat(commandStatus(f)).isEqualTo("FAILED");
    assertThat(balance(f, "settlement_reserved_amount")).isEqualByComparingTo("600");
    verify(gateway, times(1)).request(any());
  }

  @Test void mismatchedReferenceRecipientAmountCurrencyOrSuccessTimeCannotBecomePaid() {
    for (int variation = 0; variation < 6; variation++) {
      var f = due();
      Claim claim = claim(f);
      Request r = claim.request();
      transactions.settle(claim, new Result(variation == 0 ? "other" : r.reference(), variation == 1 ? "other" : r.recipient(),
          variation == 2 ? BigDecimal.ONE : r.amount(), variation == 3 ? "USD" : r.currency(),
          variation == 4 ? null : "provider-123", Status.SUCCEEDED, variation == 5 ? null : NOW.atOffset(ZoneOffset.UTC)));
      assertThat(commandStatus(f)).isEqualTo("REVIEW_REQUIRED");
      assertThat(settlementStatus(f)).isEqualTo("PAYOUT_REQUESTED");
      assertThat(balance(f, "settlement_reserved_amount")).isEqualByComparingTo("600");
      assertThat(balance(f, "settlement_paid_amount")).isEqualByComparingTo("0");
    }
  }

  @Test void fundingBlockedBeforeFirstClaimHoldsWithoutSending() {
    var f = due();
    jdbc.update("UPDATE payment_deposits SET funding_blocked=TRUE WHERE job_post_id=?", f.job());
    execution.execute(f.id());
    assertThat(settlementStatus(f)).isEqualTo("ON_HOLD");
    assertThat(commands.hasCommand(f.id())).isFalse();
    verify(gateway, never()).request(any());
  }

  @Test void concurrentClaimAndLeaseTakeoverFenceStaleResult() throws Exception {
    var f = due();
    var pool = Executors.newFixedThreadPool(2);
    try {
      var a = pool.submit(() -> claim(f));
      var b = pool.submit(() -> claim(f));
      Claim first = a.get(10, TimeUnit.SECONDS), second = b.get(10, TimeUnit.SECONDS);
      assertThat(Arrays.asList(first, second).stream().filter(Objects::nonNull).count()).isEqualTo(1);
      Claim original = first == null ? second : first;
      retryDue(f);
      Claim takeover = claim(f);
      assertThat(takeover.firstAttempt()).isFalse();
      assertThat(takeover.request()).isEqualTo(original.request());
      transactions.settle(original, result(original.request(), Status.SUCCEEDED));
      assertThat(settlementStatus(f)).isEqualTo("PAYOUT_REQUESTED");
      transactions.settle(takeover, result(takeover.request(), Status.SUCCEEDED));
      assertThat(settlementStatus(f)).isEqualTo("PAID");
    } finally { pool.shutdownNow(); }
  }

  @Test void historyFailureRollsBackPaidBalanceAndCommandTogether() {
    var f = due();
    Claim claim = claim(f);
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("test rollback"))
        .when(histories).record(f.id(), "PAYOUT_REQUESTED", "PAID", "PROVIDER_SUCCEEDED");
    assertThatThrownBy(() -> transactions.settle(claim, result(claim.request(), Status.SUCCEEDED)))
        .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    assertThat(settlementStatus(f)).isEqualTo("PAYOUT_REQUESTED");
    assertThat(commandStatus(f)).isEqualTo("AWAITING_RESULT");
    assertThat(balance(f, "settlement_reserved_amount")).isEqualByComparingTo("600");
    assertThat(balance(f, "settlement_paid_amount")).isEqualByComparingTo("0");
  }

  @Test void queryRequiresParticipantRoleAndDoesNotExposePayoutReferences() throws Exception {
    var f = due();
    String path = "/api/payments/me/settlements/" + f.id();
    mvc.perform(get(path)).andExpect(status().isUnauthorized());
    mvc.perform(get(path).header("Authorization", "Bearer " + OrderIntegrationTests.token(21, "OWNER"))).andExpect(status().isNotFound());
    mvc.perform(get(path).header("Authorization", "Bearer " + OrderIntegrationTests.token(30, "OWNER"))).andExpect(status().isNotFound());
    mvc.perform(get(path).header("Authorization", "Bearer " + OrderIntegrationTests.token(20, "ADMIN"))).andExpect(status().isUnauthorized());
    for (var member : List.of(new Object[]{20L, "OWNER"}, new Object[]{30L, "WORKER"})) {
      mvc.perform(get(path).header("Authorization", "Bearer " + OrderIntegrationTests.token((long)member[0], (String)member[1])))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data.amount").value(600))
          .andExpect(jsonPath("$.data.recipient").doesNotExist()).andExpect(jsonPath("$.data.reference").doesNotExist());
    }
    String workerToken = OrderIntegrationTests.token(30, "WORKER");
    Claim first = claim(f);
    transactions.settle(first, null);
    mvc.perform(get(path).header("Authorization", "Bearer " + workerToken))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.payoutReviewRequired").value(true));
    mvc.perform(get("/api/payments/me/settlements").param("size", "1").header("Authorization", "Bearer " + workerToken))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
    mvc.perform(get("/api/payments/me/settlements").param("beforeId", "1").header("Authorization", "Bearer " + workerToken))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    mvc.perform(get("/api/payments/me/settlements").param("size", "101").header("Authorization", "Bearer " + workerToken))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/payments/me/settlements").param("beforeId", "-1").header("Authorization", "Bearer " + workerToken))
        .andExpect(status().isBadRequest());
  }
}
