package com.workernotfound.payment;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.workernotfound.payment.domain.payment.dto.request.PaymentLockRequest;
import com.workernotfound.payment.domain.payment.entity.PaymentDeposit;
import com.workernotfound.payment.domain.payment.exception.PaymentErrorCode;
import com.workernotfound.payment.domain.payment.repository.PaymentDepositRepository;
import com.workernotfound.payment.domain.payment.service.PaymentApplicationService;
import com.workernotfound.payment.domain.refund.dto.request.RefundRequest;
import com.workernotfound.payment.domain.refund.dto.response.RefundResponse;
import com.workernotfound.payment.domain.refund.repository.RefundCommandRepository;
import com.workernotfound.payment.domain.refund.service.RefundApplicationService;
import com.workernotfound.payment.global.exception.BusinessException;
import com.workernotfound.payment.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class RefundIntegrationTests extends IntegrationTestSupport {
  static final AtomicLong IDS = new AtomicLong(700_000);
  static final String SECRET = "payment-test-internal-secret";
  @Autowired RefundApplicationService refunds;
  @Autowired PaymentApplicationService payments;
  @Autowired PaymentDepositRepository deposits;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @MockitoSpyBean RefundCommandRepository commands;

  String key() { return UUID.randomUUID().toString(); }

  // 검증된 주문 승인으로 만들어진 예치를 흉내 낸다. 런타임에 예치를 만드는 경로는 아니다.
  long deposit(String amount) {
    long job = IDS.incrementAndGet();
    deposits.saveAndFlush(PaymentDeposit.builder().jobPostId(job).ownerMemberId(20L)
        .currency("KRW").depositedAmount(new BigDecimal(amount)).build());
    jdbc.update("INSERT INTO payment_orders(id,job_post_id,job_version,owner_member_id,amount,currency,status,"
        + "payment_key,approved_at) VALUES (?,?,1,20,?,'KRW','DEPOSITED',?,CURRENT_TIMESTAMP(6))",
        orderId(job), job, new BigDecimal(amount), "pk-" + job);
    return job;
  }

  String orderId(long job) { return "order-" + job; }

  RefundRequest request(long job, String amount) {
    return new RefundRequest(job, 20L, new BigDecimal(amount), "KRW", "미사용 예치금 반환");
  }

  BigDecimal balance(long job, String column) {
    return jdbc.queryForObject("SELECT " + column + " FROM payment_deposits WHERE job_post_id = ?",
        BigDecimal.class, job);
  }

  long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  void assertError(PaymentErrorCode code, Runnable action) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
        exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
  }

  String attempt(RefundRequest request) {
    try { refunds.request(key(), request); return "REQUESTED"; }
    catch (BusinessException exception) { return ((PaymentErrorCode) exception.getErrorCode()).name(); }
  }

  String attemptLock(long job, String amount) {
    try {
      payments.lock(key(), new PaymentLockRequest(IDS.incrementAndGet(), job, 20L, 30L,
          new BigDecimal(amount), "KRW"));
      return "LOCKED";
    } catch (BusinessException exception) { return ((PaymentErrorCode) exception.getErrorCode()).name(); }
  }

  @Test
  void requestReservesAvailableAmountAndReplaysOriginalRefund() {
    long job = deposit("1000");
    String command = key();
    RefundResponse first = refunds.request(command, request(job, "400"));
    assertThat(first.status()).isEqualTo("REQUESTED");
    assertThat(first.orderId()).isEqualTo(orderId(job));
    assertThat(first.amount()).isEqualByComparingTo("400");
    assertThat(first.completedAt()).isNull();
    assertThat(refunds.request(command, request(job, "400.00"))).isEqualTo(first);
    assertThat(refunds.getRefund(Long.parseLong(first.refundId()))).isEqualTo(first);
    assertThat(balance(job, "refund_reserved_amount")).isEqualByComparingTo("400");
    assertThat(balance(job, "refunded_amount")).isEqualByComparingTo("0");
    assertThat(balance(job, "deposited_amount")).isEqualByComparingTo("1000");
    assertThat(count("SELECT COUNT(*) FROM payment_refunds WHERE job_post_id = ?", job)).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM payment_refund_histories WHERE refund_id = ? AND"
        + " previous_status IS NULL AND next_status = 'REQUESTED'", Long.parseLong(first.refundId())))
        .isEqualTo(1);
    var second = refunds.request(key(), request(job, "600"));
    assertThat(second.refundId()).isNotEqualTo(first.refundId());
    assertThat(balance(job, "refund_reserved_amount")).isEqualByComparingTo("1000");
    assertError(PaymentErrorCode.INSUFFICIENT_DEPOSIT, () -> refunds.request(key(), request(job, "1")));
  }

  @Test
  void lockedAmountIsNotRefundableAndRefundIsNotLockable() {
    long job = deposit("1000");
    assertThat(attemptLock(job, "600")).isEqualTo("LOCKED");
    assertError(PaymentErrorCode.INSUFFICIENT_DEPOSIT, () -> refunds.request(key(), request(job, "401")));
    refunds.request(key(), request(job, "400"));
    assertThat(attemptLock(job, "1")).isEqualTo("INSUFFICIENT_DEPOSIT");
    assertThat(balance(job, "locked_amount")).isEqualByComparingTo("600");
    assertThat(balance(job, "refund_reserved_amount")).isEqualByComparingTo("400");
  }

  @Test
  void rejectedCommandStaysRejectedAfterFundsBecomeAvailable() {
    long job = deposit("1000");
    String lockKey = key();
    var lock = payments.lock(lockKey, new PaymentLockRequest(IDS.incrementAndGet(), job, 20L, 30L,
        new BigDecimal("1000"), "KRW"));
    String rejected = key();
    assertError(PaymentErrorCode.INSUFFICIENT_DEPOSIT, () -> refunds.request(rejected, request(job, "500")));
    payments.release(key(), Long.parseLong(lock.paymentId()));
    assertError(PaymentErrorCode.INSUFFICIENT_DEPOSIT, () -> refunds.request(rejected, request(job, "500")));
    assertThat(balance(job, "refund_reserved_amount")).isEqualByComparingTo("0");
    assertThat(refunds.request(key(), request(job, "500")).status()).isEqualTo("REQUESTED");
  }

  @Test
  void depositMismatchMissingAndBlockedDepositsFailClosed() {
    assertError(PaymentErrorCode.DEPOSIT_NOT_FOUND,
        () -> refunds.request(key(), request(IDS.incrementAndGet(), "100")));
    long job = deposit("1000");
    assertError(PaymentErrorCode.DEPOSIT_MISMATCH,
        () -> refunds.request(key(), new RefundRequest(job, 21L, new BigDecimal("100"), "KRW", "사유")));
    assertError(PaymentErrorCode.DEPOSIT_MISMATCH,
        () -> refunds.request(key(), new RefundRequest(job, 20L, new BigDecimal("100"), "USD", "사유")));
    jdbc.update("UPDATE payment_deposits SET funding_blocked = TRUE WHERE job_post_id = ?", job);
    assertError(PaymentErrorCode.FUNDING_BLOCKED, () -> refunds.request(key(), request(job, "100")));
    assertThat(balance(job, "refund_reserved_amount")).isEqualByComparingTo("0");
    assertThat(count("SELECT COUNT(*) FROM payment_refunds WHERE job_post_id = ?", job)).isZero();
  }

  @Test
  void commandKeyCannotBeReusedForDifferentRefund() {
    long job = deposit("1000");
    String command = key();
    refunds.request(command, request(job, "100"));
    assertError(PaymentErrorCode.COMMAND_CONFLICT, () -> refunds.request(command, request(job, "101")));
    assertError(PaymentErrorCode.COMMAND_CONFLICT, () -> refunds.request(command,
        new RefundRequest(job, 20L, new BigDecimal("100"), "KRW", "다른 사유")));
    assertThat(balance(job, "refund_reserved_amount")).isEqualByComparingTo("100");
  }

  @Test
  void historyFailureRollsBackReservationRefundAndCommand() {
    long job = deposit("1000");
    String command = key();
    Mockito.doThrow(new DataAccessResourceFailureException("test failure"))
        .when(commands).history(ArgumentMatchers.anyLong(), ArgumentMatchers.isNull(),
            ArgumentMatchers.eq("REQUESTED"));
    try {
      assertThatThrownBy(() -> refunds.request(command, request(job, "100")))
          .isInstanceOf(DataAccessException.class);
    } finally { Mockito.reset(commands); }
    assertThat(balance(job, "refund_reserved_amount")).isEqualByComparingTo("0");
    assertThat(count("SELECT COUNT(*) FROM payment_refunds WHERE job_post_id = ?", job)).isZero();
    assertThat(count("SELECT COUNT(*) FROM payment_refund_commands WHERE command_key = ?", command)).isZero();
    assertThat(refunds.request(command, request(job, "100")).status()).isEqualTo("REQUESTED");
  }

  @Test
  void depositWithoutDepositedOrderIsServerFailureWithoutChanges() {
    long job = deposit("1000");
    jdbc.update("UPDATE payment_orders SET status = 'SUPERSEDED' WHERE job_post_id = ?", job);
    String command = key();
    assertThatIllegalStateException().isThrownBy(() -> refunds.request(command, request(job, "100")));
    assertThat(balance(job, "refund_reserved_amount")).isEqualByComparingTo("0");
    assertThat(count("SELECT COUNT(*) FROM payment_refund_commands WHERE command_key = ?", command)).isZero();
  }

  @Test
  void balanceConstraintIncludesRefundAmounts() {
    long job = deposit("1000");
    assertThatThrownBy(() -> jdbc.update("UPDATE payment_deposits SET refunded_amount = 600,"
        + " refund_reserved_amount = 401 WHERE job_post_id = ?", job))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_deposit_balance");
    assertThatThrownBy(() -> jdbc.update("UPDATE payment_deposits SET refunded_amount = -1"
        + " WHERE job_post_id = ?", job))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_deposit_balance");
  }

  @Test
  void simultaneousRefundsCannotOverspend() throws Exception {
    long job = deposit("1000");
    assertThat(parallel(() -> attempt(request(job, "600")), () -> attempt(request(job, "600"))))
        .containsExactlyInAnyOrder("REQUESTED", "INSUFFICIENT_DEPOSIT");
    assertThat(balance(job, "refund_reserved_amount")).isEqualByComparingTo("600");
  }

  @Test
  void simultaneousRefundAndLockCannotOverspend() throws Exception {
    long job = deposit("1000");
    assertThat(parallel(() -> attempt(request(job, "600")), () -> attemptLock(job, "600")))
        .containsAnyOf("REQUESTED", "LOCKED").contains("INSUFFICIENT_DEPOSIT");
    assertThat(balance(job, "refund_reserved_amount").add(balance(job, "locked_amount")))
        .isEqualByComparingTo("600");
  }

  @Test
  void simultaneousIdenticalCommandsCreateOneRefund() throws Exception {
    long job = deposit("1000");
    String command = key();
    var results = parallel(() -> refunds.request(command, request(job, "300")),
        () -> refunds.request(command, request(job, "300")));
    assertThat(results.get(0)).isEqualTo(results.get(1));
    assertThat(balance(job, "refund_reserved_amount")).isEqualByComparingTo("300");
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

  String body(long job, String amount, String reason) {
    return """
        {"jobPostId":%d,"ownerMemberId":20,"amount":%s,"currency":"KRW","reason":%s}
        """.formatted(job, amount, reason);
  }

  @Test
  void httpContractAuthenticationQueryAndSwagger() throws Exception {
    long job = deposit("1000");
    String body = body(job, "100", "\"미사용 예치금\"");
    mvc.perform(post("/api/payments/internal/refunds").header("Idempotency-Key", key())
        .contentType("application/json").content(body)).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/payments/internal/refunds").header("X-Internal-Secret", SECRET)
        .contentType("application/json").content(body)).andExpect(status().isBadRequest());
    String response = mvc.perform(post("/api/payments/internal/refunds").header("X-Internal-Secret", SECRET)
        .header("Idempotency-Key", key()).contentType("application/json").content(body))
        .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.refundId").isString())
        .andExpect(jsonPath("$.data.orderId").value(orderId(job)))
        .andExpect(jsonPath("$.data.status").value("REQUESTED"))
        .andReturn().getResponse().getContentAsString();
    String refundId = com.jayway.jsonpath.JsonPath.read(response, "$.data.refundId");
    mvc.perform(get("/api/payments/internal/refunds/" + refundId))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/payments/internal/refunds/" + refundId).header("X-Internal-Secret", SECRET))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.refundId").value(refundId))
        .andExpect(jsonPath("$.data.status").value("REQUESTED"));
    mvc.perform(get("/api/payments/internal/refunds/9223372036854775807").header("X-Internal-Secret", SECRET))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PAYMENT-404-002"));
    mvc.perform(get("/api/payments/internal/refunds/0").header("X-Internal-Secret", SECRET))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/payments/internal/refunds").header("X-Internal-Secret", SECRET)
        .header("Idempotency-Key", key()).contentType("application/json").content(body(job, "901", "\"사유\"")))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT-409-004"));
    mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/payments/internal/refunds']").exists())
        .andExpect(jsonPath("$.paths['/api/payments/internal/refunds/{refundId}']").exists())
        .andExpect(jsonPath("$.components.schemas.RefundRequest.properties.wholeAmount").doesNotExist());
  }

  @Test
  void httpRejectsInvalidAmountsReasonsAndKeys() throws Exception {
    long job = deposit("1000");
    for (String amount : List.of("0", "-1", "0.5", "100.5", "100.50", "100000000000000000", "null")) {
      assertInvalid(key(), body(job, amount, "\"사유\""));
    }
    for (String reason : List.of("\"\"", "\" \"", "null", "\"" + "가".repeat(201) + "\"")) {
      assertInvalid(key(), body(job, "100", reason));
    }
    for (String invalid : List.of(" ", "a b", "x".repeat(129))) {
      assertInvalid(invalid, body(job, "100", "\"사유\""));
    }
    assertInvalid(key(), """
        {"jobPostId":0,"ownerMemberId":20,"amount":100,"currency":"KRW","reason":"사유"}
        """);
    assertInvalid(key(), """
        {"jobPostId":1,"ownerMemberId":20,"amount":100,"currency":"krw","reason":"사유"}
        """);
    mvc.perform(post("/api/payments/internal/refunds").header("X-Internal-Secret", SECRET)
        .header("Idempotency-Key", key()).contentType("application/json")
        .content(body(job, "100", "\"" + "가".repeat(200) + "\""))).andExpect(status().isOk());
    assertThat(balance(job, "refund_reserved_amount")).isEqualByComparingTo("100");
    mvc.perform(post("/api/payments/internal/refunds").header("X-Internal-Secret", SECRET)
        .header("Idempotency-Key", key()).contentType("application/json")
        .content(body(job, "100.00", "\"사유\""))).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.amount").value(100));
    assertThat(balance(job, "refund_reserved_amount")).isEqualByComparingTo("200");
  }

  void assertInvalid(String key, String body) throws Exception {
    mvc.perform(post("/api/payments/internal/refunds").header("X-Internal-Secret", SECRET)
        .header("Idempotency-Key", key).contentType("application/json").content(body))
        .andExpect(status().isBadRequest());
  }
}
