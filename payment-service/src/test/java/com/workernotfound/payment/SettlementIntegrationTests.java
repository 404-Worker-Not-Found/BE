package com.workernotfound.payment;

import static com.workernotfound.payment.domain.settlement.service.SettlementReservationResult.Outcome.*;
import static org.assertj.core.api.Assertions.*;

import com.workernotfound.payment.domain.payment.dto.request.PaymentLockRequest;
import com.workernotfound.payment.domain.payment.entity.PaymentDeposit;
import com.workernotfound.payment.domain.payment.exception.PaymentErrorCode;
import com.workernotfound.payment.domain.payment.repository.PaymentDepositRepository;
import com.workernotfound.payment.domain.payment.service.PaymentApplicationService;
import com.workernotfound.payment.domain.settlement.exception.SettlementTargetNotReadyException;
import com.workernotfound.payment.domain.settlement.repository.SettlementHistoryRepository;
import com.workernotfound.payment.domain.settlement.service.*;
import com.workernotfound.payment.global.account.AccountGateService;
import com.workernotfound.payment.global.exception.BusinessException;
import com.workernotfound.payment.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class SettlementIntegrationTests extends IntegrationTestSupport {
  static final AtomicLong IDS = new AtomicLong(1_000_000);
  static final LocalDateTime COMPLETED_AT = LocalDateTime.of(2026, 10, 18, 18, 0, 0, 123_456_789);

  @Autowired SettlementReservationService settlements;
  @Autowired SettlementTargetService targets;
  @Autowired PaymentApplicationService payments;
  @Autowired PaymentDepositRepository deposits;
  @Autowired JdbcTemplate jdbc;
  @Autowired AccountGateService gates;
  @MockitoSpyBean SettlementHistoryRepository histories;

  record Fixture(long job, long matching, long paymentId, WorkCompletion completion) {}

  String key() { return UUID.randomUUID().toString(); }

  long deposit(String amount) {
    long job = IDS.incrementAndGet();
    // Provider-independent test fixture only; no runtime endpoint fabricates a deposit.
    deposits.saveAndFlush(PaymentDeposit.builder().jobPostId(job).ownerMemberId(20L)
        .currency("KRW").depositedAmount(new BigDecimal(amount)).build());
    return job;
  }

  long lock(long job, long matching, String amount) {
    var request = new PaymentLockRequest(matching, job, 20L, 30L, new BigDecimal(amount), "KRW");
    return Long.parseLong(payments.lock(key(), request).paymentId());
  }

  WorkCompletion completion(long job, long matching) {
    return new WorkCompletion(key(), IDS.incrementAndGet(), matching, job, 20L, 30L, COMPLETED_AT);
  }

  MatchConfirmation confirmation(WorkCompletion completion, long paymentId, String amount) {
    return new MatchConfirmation(key(), completion.matchingId(), 4L, completion.workId(), paymentId,
        completion.jobPostId(), 20L, 30L, new BigDecimal(amount), "KRW", COMPLETED_AT.minusHours(9));
  }

  // matching-service가 확정한 근무와 잠금을 정산 대상으로 기록한다.
  WorkCompletion confirmed(long job, long matching, long paymentId, String amount) {
    WorkCompletion completion = completion(job, matching);
    assertThat(targets.record(confirmation(completion, paymentId, amount))).isEqualTo(SettlementTargetResult.RECORDED);
    return completion;
  }

  Fixture locked(String deposit, String amount) {
    long job = deposit(deposit);
    long matching = IDS.incrementAndGet();
    long paymentId = lock(job, matching, amount);
    return new Fixture(job, matching, paymentId, confirmed(job, matching, paymentId, amount));
  }

  Map<String, Object> balance(long job) {
    return jdbc.queryForMap("SELECT deposited_amount, locked_amount, settlement_reserved_amount,"
        + " settlement_paid_amount FROM payment_deposits WHERE job_post_id = ?", job);
  }

  void assertBalance(long job, String locked, String reserved) {
    var balance = balance(job);
    assertThat((BigDecimal) balance.get("locked_amount")).isEqualByComparingTo(locked);
    assertThat((BigDecimal) balance.get("settlement_reserved_amount")).isEqualByComparingTo(reserved);
    assertThat((BigDecimal) balance.get("settlement_paid_amount")).isEqualByComparingTo("0");
  }

  String lockStatus(long paymentId) {
    return jdbc.queryForObject("SELECT status FROM payment_locks WHERE id = ?", String.class, paymentId);
  }

  long settlementCount(long paymentId) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlements WHERE payment_lock_id = ?",
        Long.class, paymentId);
  }

  void assertPaymentError(PaymentErrorCode code, Runnable action) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
        exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
  }

  @Test
  void completionTransfersFullLockAmountToSettlementReservation() {
    Fixture fixture = locked("100", "60");
    var result = settlements.reserve(fixture.completion());

    assertThat(result.outcome()).isEqualTo(RESERVED);
    assertThat(lockStatus(fixture.paymentId())).isEqualTo("TRANSFERRED");
    assertBalance(fixture.job(), "0", "60");
    var row = jdbc.queryForMap("SELECT * FROM payment_settlements WHERE id = ?", result.settlementId());
    assertThat(row.get("work_id")).isEqualTo(fixture.completion().workId());
    assertThat(row.get("payment_lock_id")).isEqualTo(fixture.paymentId());
    assertThat((BigDecimal) row.get("source_amount")).isEqualByComparingTo("60");
    assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("60");
    assertThat(row.get("status")).isEqualTo("RESERVED");
    assertThat(row.get("hold_reason")).isNull();
    assertThat(row.get("source_event_id")).isEqualTo(fixture.completion().eventId());
    assertThat(row.get("work_completed_at"))
        .isEqualTo(LocalDateTime.of(2026, 10, 18, 18, 0, 0, 123_456_000));
    assertThat(row.get("payable_at"))
        .isEqualTo(LocalDateTime.of(2026, 10, 19, 18, 0, 0, 123_456_000));
    assertThat(jdbc.queryForList("SELECT next_status FROM payment_settlement_histories WHERE settlement_id = ?",
        String.class, result.settlementId())).containsExactly("RESERVED");
    assertThat(jdbc.queryForMap("SELECT command_key, settlement_id FROM payment_status_histories"
        + " WHERE payment_id = ? AND next_status = 'TRANSFERRED'", fixture.paymentId()))
        .containsEntry("command_key", null).containsEntry("settlement_id", result.settlementId());
  }

  @Test
  void repeatedCompletionReturnsExistingSettlementWithoutMovingFundsAgain() {
    Fixture fixture = locked("100", "60");
    var first = settlements.reserve(fixture.completion());
    var redelivered = new WorkCompletion(key(), fixture.completion().workId(), fixture.matching(),
        fixture.job(), 20L, 30L, COMPLETED_AT);

    assertThat(settlements.reserve(fixture.completion()))
        .isEqualTo(new SettlementReservationResult(ALREADY_RESERVED, first.settlementId()));
    assertThat(settlements.reserve(redelivered).outcome()).isEqualTo(ALREADY_RESERVED);
    assertBalance(fixture.job(), "0", "60");
    assertThat(settlementCount(fixture.paymentId())).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlement_histories WHERE settlement_id = ?",
        Long.class, first.settlementId())).isEqualTo(1);
  }

  @Test
  void sameWorkOrLockCannotCreateAnotherSettlement() {
    Fixture fixture = locked("100", "60");
    settlements.reserve(fixture.completion());
    var otherWorkSameMatching = completion(fixture.job(), fixture.matching());
    var sameWorkOtherFacts = new WorkCompletion(key(), fixture.completion().workId(), fixture.matching(),
        fixture.job(), 20L, 30L, COMPLETED_AT.plusMinutes(1));

    assertThat(settlements.reserve(otherWorkSameMatching).outcome()).isEqualTo(WORK_CONFLICT);
    assertThat(settlements.reserve(sameWorkOtherFacts).outcome()).isEqualTo(WORK_CONFLICT);
    assertThat(settlementCount(fixture.paymentId())).isEqualTo(1);
    assertBalance(fixture.job(), "0", "60");
  }

  @Test
  void definitiveRejectionsLeaveLockAndBalanceUnchanged() {
    Fixture fixture = locked("100", "60");
    var completion = fixture.completion();
    var otherWorker = new WorkCompletion(key(), completion.workId(), fixture.matching(), fixture.job(),
        20L, 31L, COMPLETED_AT);
    var otherJob = new WorkCompletion(key(), completion.workId(), fixture.matching(), fixture.job() + 1,
        20L, 30L, COMPLETED_AT);
    var unknownMatching = completion(fixture.job(), IDS.incrementAndGet());
    var missingTime = new WorkCompletion(key(), completion.workId(), fixture.matching(), fixture.job(),
        20L, 30L, null);
    var missingEvent = new WorkCompletion(" ", completion.workId(), fixture.matching(), fixture.job(),
        20L, 30L, COMPLETED_AT);

    assertThat(settlements.reserve(otherWorker).outcome()).isEqualTo(LOCK_MISMATCH);
    assertThat(settlements.reserve(otherJob).outcome()).isEqualTo(LOCK_MISMATCH);
    assertThatThrownBy(() -> settlements.reserve(unknownMatching))
        .isInstanceOf(SettlementTargetNotReadyException.class);
    assertThat(settlements.reserve(missingTime).outcome()).isEqualTo(INVALID_COMPLETION);
    assertThat(settlements.reserve(missingEvent).outcome()).isEqualTo(INVALID_COMPLETION);
    assertThat(settlements.reserve(otherWorker).isRejected()).isTrue();
    assertThat(lockStatus(fixture.paymentId())).isEqualTo("LOCKED");
    assertThat(settlementCount(fixture.paymentId())).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_matching_slots WHERE matching_id = ?",
        Long.class, unknownMatching.matchingId())).isZero();
    assertBalance(fixture.job(), "60", "0");
  }

  @Test
  void releasedLockIsNotSettledAndExistingLocksAreNotBackfilled() {
    Fixture released = locked("100", "60");
    payments.release(key(), released.paymentId());
    Fixture untouched = locked("100", "40");

    assertThat(settlements.reserve(released.completion()).outcome()).isEqualTo(LOCK_NOT_FOUND);
    assertThat(lockStatus(released.paymentId())).isEqualTo("RELEASED");
    assertThat(settlementCount(released.paymentId())).isZero();
    assertBalance(released.job(), "0", "0");
    assertThat(lockStatus(untouched.paymentId())).isEqualTo("LOCKED");
    assertThat(settlementCount(untouched.paymentId())).isZero();
  }

  @Test
  void amountBoundaries() {
    Fixture full = locked("100", "100");
    assertThat(settlements.reserve(full.completion()).outcome()).isEqualTo(RESERVED);
    assertBalance(full.job(), "0", "100");
    assertPaymentError(PaymentErrorCode.INSUFFICIENT_DEPOSIT,
        () -> lock(full.job(), IDS.incrementAndGet(), "1"));

    Fixture fractional = locked("100", "60.50");
    assertThat(settlements.reserve(fractional.completion()).outcome()).isEqualTo(UNSUPPORTED_AMOUNT);
    assertThat(lockStatus(fractional.paymentId())).isEqualTo("LOCKED");
    assertBalance(fractional.job(), "60.50", "0");
  }

  @Test
  void fundingBlockedDepositHoldsSettlement() {
    Fixture fixture = locked("100", "60");
    jdbc.update("UPDATE payment_deposits SET funding_blocked = TRUE WHERE job_post_id = ?", fixture.job());
    var result = settlements.reserve(fixture.completion());

    assertThat(result.outcome()).isEqualTo(RESERVED);
    assertThat(jdbc.queryForMap("SELECT status, hold_reason FROM payment_settlements WHERE id = ?",
        result.settlementId())).containsEntry("status", "ON_HOLD").containsEntry("hold_reason", "FUNDING_BLOCKED");
    assertThat(jdbc.queryForList("SELECT next_status FROM payment_settlement_histories WHERE settlement_id = ?"
        + " ORDER BY id", String.class, result.settlementId())).containsExactly("RESERVED", "ON_HOLD");
    assertBalance(fixture.job(), "0", "60");
  }

  @Test
  void transferredLockRejectsNewSagaReleaseButKeepsEarlierCommandResults() {
    long job = deposit("100");
    long matching = IDS.incrementAndGet();
    var request = new PaymentLockRequest(matching, job, 20L, 30L, new BigDecimal("60"), "KRW");
    String firstCreate = key();
    long firstPayment = Long.parseLong(payments.lock(firstCreate, request).paymentId());
    String firstRelease = key();
    payments.release(firstRelease, firstPayment);
    String secondCreate = key();
    long secondPayment = Long.parseLong(payments.lock(secondCreate, request).paymentId());
    settlements.reserve(confirmed(job, matching, secondPayment, "60"));

    payments.release(firstRelease, firstPayment);
    assertThat(payments.lock(firstCreate, request).paymentId()).isEqualTo(String.valueOf(firstPayment));
    assertThat(payments.lock(secondCreate, request).paymentId()).isEqualTo(String.valueOf(secondPayment));
    String lateRelease = key();
    assertPaymentError(PaymentErrorCode.LOCK_TRANSFERRED_TO_SETTLEMENT,
        () -> payments.release(lateRelease, secondPayment));
    assertPaymentError(PaymentErrorCode.LOCK_TRANSFERRED_TO_SETTLEMENT,
        () -> payments.release(lateRelease, secondPayment));
    assertPaymentError(PaymentErrorCode.ACTIVE_LOCK_EXISTS, () -> payments.lock(key(), request));
    assertThat(lockStatus(secondPayment)).isEqualTo("TRANSFERRED");
    assertBalance(job, "0", "60");
    assertThat(jdbc.queryForObject("SELECT rejection FROM payment_commands WHERE command_key = ?",
        String.class, lateRelease)).isEqualTo("LOCK_TRANSFERRED_TO_SETTLEMENT");
  }

  @Test
  void historyFailureRollsBackTransferAndAllowsRetry() {
    Fixture fixture = locked("100", "60");
    Mockito.doThrow(new DataAccessResourceFailureException("test failure")).when(histories)
        .record(ArgumentMatchers.anyLong(), ArgumentMatchers.isNull(), ArgumentMatchers.eq("RESERVED"),
            ArgumentMatchers.isNull());
    try {
      assertThatThrownBy(() -> settlements.reserve(fixture.completion()))
          .isInstanceOf(DataAccessResourceFailureException.class);
    } finally { Mockito.reset(histories); }

    assertThat(lockStatus(fixture.paymentId())).isEqualTo("LOCKED");
    assertThat(settlementCount(fixture.paymentId())).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_status_histories WHERE payment_id = ?",
        Long.class, fixture.paymentId())).isEqualTo(1);
    assertBalance(fixture.job(), "60", "0");
    assertThat(settlements.reserve(fixture.completion()).outcome()).isEqualTo(RESERVED);
    assertBalance(fixture.job(), "0", "60");
  }

  @Test
  void concurrentIdenticalCompletionsCreateOneSettlement() throws Exception {
    Fixture fixture = locked("100", "60");
    var results = parallel(() -> settlements.reserve(fixture.completion()).outcome(),
        () -> settlements.reserve(fixture.completion()).outcome());

    assertThat(results).containsExactlyInAnyOrder(RESERVED, ALREADY_RESERVED);
    assertThat(settlementCount(fixture.paymentId())).isEqualTo(1);
    assertBalance(fixture.job(), "0", "60");
  }

  @Test
  void concurrentReservationAndSagaReleaseApplyExactlyOne() throws Exception {
    Fixture fixture = locked("100", "60");
    var results = parallel(() -> settlements.reserve(fixture.completion()).outcome().name(),
        () -> releaseAttempt(fixture.paymentId()));

    if (results.contains("RESERVED")) {
      assertThat(results).containsExactlyInAnyOrder("RESERVED", "LOCK_TRANSFERRED_TO_SETTLEMENT");
      assertThat(lockStatus(fixture.paymentId())).isEqualTo("TRANSFERRED");
      assertBalance(fixture.job(), "0", "60");
    } else {
      assertThat(results).containsExactlyInAnyOrder("LOCK_NOT_FOUND", "RELEASED");
      assertThat(lockStatus(fixture.paymentId())).isEqualTo("RELEASED");
      assertBalance(fixture.job(), "0", "0");
    }
  }

  @Test
  void concurrentReservationAndNewLockCannotOverspendDeposit() throws Exception {
    Fixture fixture = locked("100", "60");
    long otherMatching = IDS.incrementAndGet();
    var results = parallel(() -> settlements.reserve(fixture.completion()).outcome().name(),
        () -> lockAttempt(fixture.job(), otherMatching, "50"));

    assertThat(results).containsExactlyInAnyOrder("RESERVED", "INSUFFICIENT_DEPOSIT");
    assertBalance(fixture.job(), "0", "60");
    assertThat(lockAttempt(fixture.job(), otherMatching, "40")).isEqualTo("LOCKED");
    assertBalance(fixture.job(), "40", "60");
  }

  @Test
  void unpaidSettlementKeepsBlockingWorkerWithdrawal() {
    long job = deposit("100");
    long matching = IDS.incrementAndGet();
    long worker = IDS.incrementAndGet();
    payments.lock(key(), new PaymentLockRequest(matching, job, 20L, worker, new BigDecimal("60"), "KRW"));
    assertThat(gates.transition(worker, key(), "prepare").state()).isEqualTo("REJECTED");

    long paymentId = jdbc.queryForObject("SELECT id FROM payment_locks WHERE matching_id = ?", Long.class, matching);
    var completion = new WorkCompletion(key(), IDS.incrementAndGet(), matching, job, 20L, worker, COMPLETED_AT);
    targets.record(new MatchConfirmation(key(), matching, 4L, completion.workId(), paymentId, job, 20L, worker,
        new BigDecimal("60"), "KRW", COMPLETED_AT));
    assertThat(settlements.reserve(completion).outcome()).isEqualTo(RESERVED);
    assertThat(gates.transition(worker, key(), "prepare").state()).isEqualTo("REJECTED");
  }

  @Test
  void completionBeforeConfirmationIsRetryableAndChangesNothing() {
    long job = deposit("100");
    long matching = IDS.incrementAndGet();
    long paymentId = lock(job, matching, "60");
    WorkCompletion completion = completion(job, matching);

    assertThatThrownBy(() -> settlements.reserve(completion)).isInstanceOf(SettlementTargetNotReadyException.class);
    assertThat(lockStatus(paymentId)).isEqualTo("LOCKED");
    assertThat(settlementCount(paymentId)).isZero();

    targets.record(confirmation(completion, paymentId, "60"));
    assertThat(settlements.reserve(completion).outcome()).isEqualTo(RESERVED);
    assertBalance(job, "0", "60");
  }

  // 이전 Saga 시도에서 해제된 잠금은 확정 대상이 될 수 없고, 활성 잠금이 그 자리를 대신 정산하지도 않는다.
  @Test
  void earlierSagaAttemptLockIsNeverSettled() {
    long job = deposit("100");
    long matching = IDS.incrementAndGet();
    long earlier = lock(job, matching, "60");
    payments.release(key(), earlier);
    long current = lock(job, matching, "60");
    WorkCompletion completion = completion(job, matching);

    assertThat(targets.record(confirmation(completion, earlier, "60"))).isEqualTo(SettlementTargetResult.LOCK_NOT_FOUND);
    assertThatThrownBy(() -> settlements.reserve(completion)).isInstanceOf(SettlementTargetNotReadyException.class);
    assertThat(targets.record(confirmation(completion, current, "60"))).isEqualTo(SettlementTargetResult.RECORDED);
    var result = settlements.reserve(completion);

    assertThat(result.outcome()).isEqualTo(RESERVED);
    assertThat(jdbc.queryForObject("SELECT payment_lock_id FROM payment_settlements WHERE id = ?", Long.class,
        result.settlementId())).isEqualTo(current);
    assertThat(lockStatus(earlier)).isEqualTo("RELEASED");
    assertThat(settlementCount(earlier)).isZero();
    assertBalance(job, "0", "60");
  }

  @Test
  void confirmationMustMatchOwnedLock() {
    long job = deposit("100");
    long matching = IDS.incrementAndGet();
    long paymentId = lock(job, matching, "60");
    WorkCompletion completion = completion(job, matching);
    var base = confirmation(completion, paymentId, "60");
    var otherAmount = confirmation(completion, paymentId, "50");
    var otherWorker = new MatchConfirmation(key(), matching, 4L, completion.workId(), paymentId, job, 20L, 31L,
        new BigDecimal("60"), "KRW", COMPLETED_AT);
    var otherJob = new MatchConfirmation(key(), matching, 4L, completion.workId(), paymentId, job + 1, 20L, 30L,
        new BigDecimal("60"), "KRW", COMPLETED_AT);
    var otherCurrency = new MatchConfirmation(key(), matching, 4L, completion.workId(), paymentId, job, 20L, 30L,
        new BigDecimal("60"), "USD", COMPLETED_AT);
    var unknownMatching = new MatchConfirmation(key(), IDS.incrementAndGet(), 4L, IDS.incrementAndGet(), paymentId,
        job, 20L, 30L, new BigDecimal("60"), "KRW", COMPLETED_AT);
    var missingWork = new MatchConfirmation(key(), matching, 4L, null, paymentId, job, 20L, 30L,
        new BigDecimal("60"), "KRW", COMPLETED_AT);

    assertThat(targets.record(otherAmount)).isEqualTo(SettlementTargetResult.LOCK_MISMATCH);
    assertThat(targets.record(otherWorker)).isEqualTo(SettlementTargetResult.LOCK_MISMATCH);
    assertThat(targets.record(otherJob)).isEqualTo(SettlementTargetResult.LOCK_MISMATCH);
    assertThat(targets.record(otherCurrency)).isEqualTo(SettlementTargetResult.LOCK_MISMATCH);
    assertThat(targets.record(unknownMatching)).isEqualTo(SettlementTargetResult.LOCK_NOT_FOUND);
    assertThat(targets.record(missingWork)).isEqualTo(SettlementTargetResult.INVALID_CONFIRMATION);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlement_targets WHERE matching_id IN (?, ?)",
        Long.class, matching, unknownMatching.matchingId())).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_matching_slots WHERE matching_id = ?",
        Long.class, unknownMatching.matchingId())).isZero();

    assertThat(targets.record(base)).isEqualTo(SettlementTargetResult.RECORDED);
    var redelivered = new MatchConfirmation(key(), matching, 5L, completion.workId(), paymentId, job, 20L, 30L,
        new BigDecimal("60.00"), "KRW", COMPLETED_AT.plusSeconds(1));
    var otherWork = new MatchConfirmation(key(), matching, 4L, IDS.incrementAndGet(), paymentId, job, 20L, 30L,
        new BigDecimal("60"), "KRW", COMPLETED_AT);
    assertThat(targets.record(redelivered)).isEqualTo(SettlementTargetResult.ALREADY_RECORDED);
    assertThat(targets.record(otherWork)).isEqualTo(SettlementTargetResult.TARGET_CONFLICT);
    assertThat(jdbc.queryForMap("SELECT work_id, matching_revision, source_event_id FROM payment_settlement_targets"
        + " WHERE matching_id = ?", matching)).containsEntry("work_id", completion.workId())
        .containsEntry("matching_revision", 4L).containsEntry("source_event_id", base.eventId());
  }

  // 대상과 다른 근무·참여자의 근무 완료는 대상 잠금을 정산하지 않는다.
  @Test
  void completionMustMatchConfirmedTarget() {
    Fixture fixture = locked("100", "60");
    var completion = fixture.completion();
    var otherWork = completion(fixture.job(), fixture.matching());
    var otherOwner = new WorkCompletion(key(), completion.workId(), fixture.matching(), fixture.job(),
        21L, 30L, COMPLETED_AT);

    assertThat(settlements.reserve(otherWork).outcome()).isEqualTo(WORK_CONFLICT);
    assertThat(settlements.reserve(otherOwner).outcome()).isEqualTo(LOCK_MISMATCH);
    assertThat(lockStatus(fixture.paymentId())).isEqualTo("LOCKED");
    assertBalance(fixture.job(), "60", "0");
    assertThat(settlements.reserve(completion).outcome()).isEqualTo(RESERVED);
  }

  String releaseAttempt(long paymentId) {
    try { payments.release(key(), paymentId); return "RELEASED"; }
    catch (BusinessException exception) { return ((PaymentErrorCode) exception.getErrorCode()).name(); }
  }

  String lockAttempt(long job, long matching, String amount) {
    try { lock(job, matching, amount); return "LOCKED"; }
    catch (BusinessException exception) { return ((PaymentErrorCode) exception.getErrorCode()).name(); }
  }

  <T> List<T> parallel(Callable<T> first, Callable<T> second) throws Exception {
    var executor = Executors.newFixedThreadPool(2);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try {
      List<Future<T>> futures = new ArrayList<>();
      for (Callable<T> task : List.of(first, second)) futures.add(executor.submit(() -> {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
        return task.call();
      }));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
    } finally { start.countDown(); executor.shutdownNow(); }
  }
}
