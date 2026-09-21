package com.workernotfound.payment;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.workernotfound.payment.domain.payment.dto.request.PaymentLockRequest;
import com.workernotfound.payment.domain.payment.dto.response.PaymentLockResponse;
import com.workernotfound.payment.domain.payment.entity.PaymentDeposit;
import com.workernotfound.payment.domain.payment.exception.PaymentErrorCode;
import com.workernotfound.payment.domain.payment.repository.*;
import com.workernotfound.payment.domain.payment.service.PaymentApplicationService;
import com.workernotfound.payment.global.exception.BusinessException;
import com.workernotfound.payment.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@AutoConfigureMockMvc
class PaymentIntegrationTests extends IntegrationTestSupport {
  static final AtomicLong IDS = new AtomicLong(100);
  @Autowired PaymentApplicationService service;
  @Autowired PaymentDepositRepository deposits;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @MockitoSpyBean PaymentCommandRepository commands;

  String key() { return UUID.randomUUID().toString(); }

  long deposit(String amount) {
    long job = IDS.incrementAndGet();
    // Provider-independent test fixture only; no runtime endpoint fabricates a deposit.
    deposits.saveAndFlush(PaymentDeposit.builder().jobPostId(job).ownerMemberId(20L)
        .currency("KRW").depositedAmount(new BigDecimal(amount)).build());
    return job;
  }

  PaymentLockRequest request(long job, long matching, String amount) {
    return new PaymentLockRequest(matching, job, 20L, 30L, new BigDecimal(amount), "KRW");
  }

  BigDecimal locked(long job) {
    return jdbc.queryForObject("SELECT locked_amount FROM payment_deposits WHERE job_post_id = ?", BigDecimal.class, job);
  }

  void assertError(PaymentErrorCode code, Runnable action) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
        exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
  }

  @Test
  void retriesAndLateCompensationPreserveReplacement() {
    long job = deposit("100");
    var request = request(job, IDS.incrementAndGet(), "80");
    String create = key();
    var first = service.lock(create, request);
    assertThat(service.lock(create, request(job, request.matchingId(), "80.00"))).isEqualTo(first);
    String release = key();
    long oldId = Long.parseLong(first.paymentId());
    service.release(release, oldId);
    service.release(release, oldId);
    assertThat(locked(job)).isEqualByComparingTo("0");
    assertThat(service.lock(create, request)).isEqualTo(first);
    var next = service.lock(key(), request);
    assertThat(next).isNotEqualTo(first);
    service.release(key(), oldId);
    assertThat(locked(job)).isEqualByComparingTo("80");
    assertThat(jdbc.queryForObject("SELECT active_payment_id FROM payment_matching_slots WHERE matching_id = ?",
        Long.class, request.matchingId())).isEqualTo(Long.parseLong(next.paymentId()));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_status_histories WHERE payment_id = ?",
        Long.class, oldId)).isEqualTo(2);
  }

  @Test
  void missingDepositAndInsufficientFundsFailClosed() {
    assertError(PaymentErrorCode.DEPOSIT_NOT_FOUND,
        () -> service.lock(key(), request(IDS.incrementAndGet(), IDS.incrementAndGet(), "1")));
    long job = deposit("10");
    assertError(PaymentErrorCode.INSUFFICIENT_DEPOSIT,
        () -> service.lock(key(), request(job, IDS.incrementAndGet(), "11")));
    assertThat(locked(job)).isEqualByComparingTo("0");
  }

  @Test
  void rejectedCommandCannotSucceedAfterFundsBecomeAvailable() {
    long job = deposit("100");
    var first = service.lock(key(), request(job, IDS.incrementAndGet(), "100"));
    String rejected = key();
    var request = request(job, IDS.incrementAndGet(), "50");
    assertError(PaymentErrorCode.INSUFFICIENT_DEPOSIT, () -> service.lock(rejected, request));
    service.release(key(), Long.parseLong(first.paymentId()));
    assertError(PaymentErrorCode.INSUFFICIENT_DEPOSIT, () -> service.lock(rejected, request));
    assertThat(locked(job)).isEqualByComparingTo("0");
    assertThat(service.lock(key(), request).paymentId()).isNotBlank();
  }

  @Test
  void ownerAndCurrencyMustMatchDeposit() {
    long job = deposit("100");
    assertError(PaymentErrorCode.DEPOSIT_MISMATCH, () -> service.lock(key(),
        new PaymentLockRequest(IDS.incrementAndGet(), job, 21L, 30L, BigDecimal.ONE, "KRW")));
    assertError(PaymentErrorCode.DEPOSIT_MISMATCH, () -> service.lock(key(),
        new PaymentLockRequest(IDS.incrementAndGet(), job, 20L, 30L, BigDecimal.ONE, "USD")));
    assertThat(locked(job)).isEqualByComparingTo("0");
  }

  @Test
  void commandReuseAndDuplicateMatchingAreRejected() {
    long job = deposit("100");
    var request = request(job, IDS.incrementAndGet(), "30");
    String command = key();
    var result = service.lock(command, request);
    assertError(PaymentErrorCode.COMMAND_CONFLICT,
        () -> service.lock(command, request(job, request.matchingId(), "31")));
    assertError(PaymentErrorCode.COMMAND_CONFLICT,
        () -> service.release(command, Long.parseLong(result.paymentId())));
    assertError(PaymentErrorCode.ACTIVE_LOCK_EXISTS, () -> service.lock(key(), request));
    assertThat(locked(job)).isEqualByComparingTo("30");
  }

  @Test
  void historyFailureRollsBackBalancePaymentAndCommand() {
    long job = deposit("100");
    var request = request(job, IDS.incrementAndGet(), "30");
    String command = key();
    org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("test failure"))
        .when(commands).history(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("LOCKED"), org.mockito.ArgumentMatchers.eq(command));
    try {
      assertThatThrownBy(() -> service.lock(command, request))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    } finally { org.mockito.Mockito.reset(commands); }
    assertThat(locked(job)).isEqualByComparingTo("0");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_locks WHERE matching_id = ?",
        Long.class, request.matchingId())).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_commands WHERE command_key = ?",
        Long.class, command)).isZero();
    assertThat(service.lock(command, request).paymentId()).isNotBlank();
  }

  @Test
  void releaseHistoryFailureRollsBackBalanceAndStatus() {
    long job = deposit("100");
    long payment = Long.parseLong(service.lock(key(), request(job, IDS.incrementAndGet(), "60")).paymentId());
    String release = key();
    org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("test failure"))
        .when(commands).history(payment, "LOCKED", "RELEASED", release);
    try {
      assertThatThrownBy(() -> service.release(release, payment))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    } finally { org.mockito.Mockito.reset(commands); }
    assertThat(locked(job)).isEqualByComparingTo("60");
    assertThat(jdbc.queryForObject("SELECT status FROM payment_locks WHERE id = ?", String.class, payment))
        .isEqualTo("LOCKED");
    service.release(release, payment);
    assertThat(locked(job)).isEqualByComparingTo("0");
  }

  @Test
  void simultaneousIdenticalCommandsReturnOnePayment() throws Exception {
    long job = deposit("100");
    var request = request(job, IDS.incrementAndGet(), "60");
    String command = key();
    var results = parallel(() -> service.lock(command, request), () -> service.lock(command, request));
    assertThat(results.get(0)).isEqualTo(results.get(1));
    assertThat(locked(job)).isEqualByComparingTo("60");
  }

  @Test
  void simultaneousDifferentMatchesCannotOverspend() throws Exception {
    long job = deposit("100");
    var first = request(job, IDS.incrementAndGet(), "60");
    var second = request(job, IDS.incrementAndGet(), "60");
    var results = parallel(() -> attempt(first), () -> attempt(second));
    assertThat(results).containsExactlyInAnyOrder("LOCKED", "INSUFFICIENT_DEPOSIT");
    assertThat(locked(job)).isEqualByComparingTo("60");
  }

  @Test
  void simultaneousDifferentCommandsCannotDuplicateMatching() throws Exception {
    long job = deposit("100");
    var request = request(job, IDS.incrementAndGet(), "20");
    assertThat(parallel(() -> attempt(request), () -> attempt(request)))
        .containsExactlyInAnyOrder("LOCKED", "ACTIVE_LOCK_EXISTS");
    assertThat(locked(job)).isEqualByComparingTo("20");
  }

  @Test
  void concurrentCompensationsReturnFundsOnlyOnce() throws Exception {
    long job = deposit("100");
    var payment = service.lock(key(), request(job, IDS.incrementAndGet(), "60"));
    Callable<String> release = () -> { service.release(key(), Long.parseLong(payment.paymentId())); return "OK"; };
    parallel(release, release);
    assertThat(locked(job)).isEqualByComparingTo("0");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_status_histories WHERE payment_id = ?",
        Long.class, Long.parseLong(payment.paymentId()))).isEqualTo(2);
  }

  String attempt(PaymentLockRequest request) {
    try { service.lock(key(), request); return "LOCKED"; }
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

  @Test
  void httpContractAuthenticationAndSwagger() throws Exception {
    long job = deposit("100");
    String body = """
        {"matchingId":%d,"jobPostId":%d,"ownerMemberId":20,"workerMemberId":30,"amount":60,"currency":"KRW"}
        """.formatted(IDS.incrementAndGet(), job);
    mvc.perform(post("/api/payments/internal/locks").contentType("application/json").content(body))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/payments/internal/locks").header("X-Internal-Secret", "payment-test-internal-secret")
        .contentType("application/json").content(body)).andExpect(status().isBadRequest());
    String command = key();
    mvc.perform(post("/api/payments/internal/locks").header("X-Internal-Secret", "payment-test-internal-secret")
        .header("Idempotency-Key", command).contentType("application/json").content(body))
        .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.paymentId").isString());
    mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/payments/internal/locks']").exists());
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
  }

  @Test
  void httpReleaseAndBusinessErrorsKeepSagaContract() throws Exception {
    long job = deposit("100");
    long payment = Long.parseLong(service.lock(key(), request(job, IDS.incrementAndGet(), "60")).paymentId());
    mvc.perform(post("/api/payments/internal/locks/" + payment + "/release")
        .header("X-Internal-Secret", "payment-test-internal-secret").header("Idempotency-Key", key()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    mvc.perform(post("/api/payments/internal/locks/9223372036854775807/release")
        .header("X-Internal-Secret", "payment-test-internal-secret").header("Idempotency-Key", key()))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PAYMENT-404-001"));
    mvc.perform(get("/api/payments/internal/locks").header("X-Internal-Secret", "payment-test-internal-secret"))
        .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("POST")));
    assertThat(locked(job)).isEqualByComparingTo("0");
  }

  @Test
  void unexpectedFailureIsSafe500AndNotDefinitiveRejection() throws Exception {
    String command = key();
    org.mockito.Mockito.doThrow(new IllegalArgumentException("sensitive-database-details"))
        .when(commands).lockCommand(org.mockito.ArgumentMatchers.eq(command), org.mockito.ArgumentMatchers.anyString());
    try {
      mvc.perform(post("/api/payments/internal/locks").header("X-Internal-Secret", "payment-test-internal-secret")
          .header("Idempotency-Key", command).contentType("application/json").content("""
              {"matchingId":1,"jobPostId":1,"ownerMemberId":20,"workerMemberId":30,"amount":1,"currency":"KRW"}
              """))
          .andExpect(status().isInternalServerError())
          .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sensitive-database-details"))));
    } finally { org.mockito.Mockito.reset(commands); }
  }

  @Test
  void httpRejectsInvalidAmountsIdsCurrenciesAndKeys() throws Exception {
    String body = """
        {"matchingId":1,"jobPostId":1,"ownerMemberId":20,"workerMemberId":30,"amount":%s,"currency":"KRW"}
        """;
    for (String amount : List.of("0", "-1", "0.001", "100000000000000000", "null")) {
      mvc.perform(post("/api/payments/internal/locks").header("X-Internal-Secret", "payment-test-internal-secret")
          .header("Idempotency-Key", key()).contentType("application/json").content(body.formatted(amount)))
          .andExpect(status().isBadRequest());
    }
    for (String invalid : List.of(" ", "a b", "x".repeat(129))) {
      mvc.perform(post("/api/payments/internal/locks").header("X-Internal-Secret", "payment-test-internal-secret")
          .header("Idempotency-Key", invalid).contentType("application/json").content(body.formatted("1")))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(post("/api/payments/internal/locks/-1/release").header("X-Internal-Secret", "payment-test-internal-secret")
        .header("Idempotency-Key", key())).andExpect(status().isBadRequest());
  }
}
