package com.workernotfound.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.workernotfound.payment.domain.order.dto.*;
import com.workernotfound.payment.domain.order.exception.OrderErrorCode;
import com.workernotfound.payment.domain.order.repository.*;
import com.workernotfound.payment.domain.order.service.*;
import com.workernotfound.payment.domain.payment.dto.request.PaymentLockRequest;
import com.workernotfound.payment.domain.payment.service.PaymentApplicationService;
import com.workernotfound.payment.external.job.FundingJobClient;
import com.workernotfound.payment.external.toss.*;
import com.workernotfound.payment.global.exception.BusinessException;
import com.workernotfound.payment.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class OrderIntegrationTests extends IntegrationTestSupport {
  static final AtomicLong IDS = new AtomicLong(100000);
  @Autowired OrderApplicationService service;
  @Autowired OrderTransactionService transactions;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  PaymentOrderRepository orders;

  @Autowired FundingNotificationRepository notifications;
  @Autowired PaymentApplicationService locks;
  @Autowired PaymentRecovery recovery;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @MockitoBean TossGateway toss;
  @MockitoBean FundingJobClient job;

  String key() {
    return UUID.randomUUID().toString();
  }

  CreateOrderRequest request(long job, long version) {
    return new CreateOrderRequest(job, version, 20L, new BigDecimal("10000"), "KRW");
  }

  OrderResponse order() {
    return service.create(key(), request(IDS.incrementAndGet(), 1));
  }

  ConfirmOrderRequest confirmRequest(String key) {
    return new ConfirmOrderRequest(key, new BigDecimal("10000"));
  }

  TossPayment payment(OrderResponse order, String key, String status) {
    return new TossPayment(
        key,
        order.orderId(),
        status,
        "KRW",
        "카드",
        order.amount(),
        order.amount(),
        OffsetDateTime.now());
  }

  void approve(OrderResponse order, String key) {
    when(toss.confirm(order.orderId(), key, order.amount()))
        .thenReturn(payment(order, key, "DONE"));
    service.confirm(order.orderId(), 20L, confirmRequest(key));
  }

  long deposits(OrderResponse order) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM payment_deposits WHERE job_post_id=?", Long.class, order.jobPostId());
  }

  @Test
  void createsOneImmutableOrderAndSupersedesOnlyUnconfirmedVersion() {
    String command = key();
    var request = request(IDS.incrementAndGet(), 1);
    var first = service.create(command, request);
    assertThat(service.create(command, request)).isEqualTo(first);
    assertThatThrownBy(() -> service.create(command, request(request.jobPostId(), 2)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.create(key(), request)).isInstanceOf(BusinessException.class);
    var replacement = service.create(key(), request(request.jobPostId(), 2));
    assertThat(service.getOrder(first.orderId(), 20L).status()).isEqualTo("SUPERSEDED");
    assertThat(service.create(command, request).orderId()).isEqualTo(first.orderId());
    assertThatThrownBy(() -> service.confirm(first.orderId(), 20L, confirmRequest(key())))
        .isInstanceOf(BusinessException.class);
    approve(replacement, key());
    assertThatThrownBy(() -> service.create(key(), request(request.jobPostId(), 3)))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void confirmsOnlyOwnerAndStoredAmountAndCreditsOnce() {
    var order = order();
    String key = key();
    assertThatThrownBy(() -> service.confirm(order.orderId(), 21L, confirmRequest(key)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () ->
                service.confirm(order.orderId(), 20L, new ConfirmOrderRequest(key, BigDecimal.ONE)))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(toss);
    approve(order, key);
    assertThat(service.confirm(order.orderId(), 20L, confirmRequest(key)).status())
        .isEqualTo("DEPOSITED");
    assertThat(deposits(order)).isEqualTo(1);
    verify(toss, times(1)).confirm(order.orderId(), key, order.amount());
    assertThat(
            locks
                .lock(
                    key(),
                    new PaymentLockRequest(
                        IDS.incrementAndGet(),
                        order.jobPostId(),
                        20L,
                        30L,
                        new BigDecimal("10000"),
                        "KRW"))
                .paymentId())
        .isNotBlank();
  }

  @Test
  void approvalRunsOutsideDatabaseTransactionAndFailedCreditRollsBack() {
    var order = order();
    String key = key();
    when(toss.confirm(order.orderId(), key, order.amount()))
        .thenAnswer(
            invocation -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              return payment(order, key, "DONE");
            });
    doThrow(
            new org.springframework.dao.DataAccessResourceFailureException(
                "test persistence failure"))
        .when(orders)
        .transition(order.orderId(), "DEPOSITED");
    try {
      assertThatThrownBy(() -> service.confirm(order.orderId(), 20L, confirmRequest(key)))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    } finally {
      reset(orders);
    }
    assertThat(deposits(order)).isZero();
    when(toss.getPayment(key)).thenReturn(payment(order, key, "DONE"));
    service.recover(order.orderId());
    assertThat(deposits(order)).isEqualTo(1);
    verify(toss, times(1)).confirm(order.orderId(), key, order.amount());
  }

  @Test
  void lostApprovalResponseRecoversThroughLookupWithoutSecondCharge() {
    var order = order();
    String key = key();
    when(toss.confirm(order.orderId(), key, order.amount()))
        .thenThrow(new BusinessException(OrderErrorCode.PROVIDER_UNAVAILABLE));
    assertThatThrownBy(() -> service.confirm(order.orderId(), 20L, confirmRequest(key)))
        .isInstanceOf(BusinessException.class);
    assertThat(service.getOrder(order.orderId(), 20L).status()).isEqualTo("CONFIRMING");
    assertThat(deposits(order)).isZero();
    assertThatThrownBy(() -> service.create(key(), request(order.jobPostId(), 2)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.confirm(order.orderId(), 20L, confirmRequest(key())))
        .isInstanceOf(BusinessException.class);
    when(toss.getPayment(key)).thenReturn(payment(order, key, "DONE"));
    service.recover(order.orderId());
    assertThat(deposits(order)).isEqualTo(1);
    verify(toss, times(1)).confirm(order.orderId(), key, order.amount());
  }

  @Test
  void retryUsesSameApprovalCommandWhileStillInProgress() {
    var order = order();
    String key = key();
    transactions.prepare(order.orderId(), 20L, confirmRequest(key));
    jdbc.update(
        "UPDATE payment_orders SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND)"
            + " WHERE id=?",
        order.orderId());
    when(toss.getPayment(key)).thenReturn(payment(order, key, "IN_PROGRESS"));
    when(toss.confirm(order.orderId(), key, order.amount()))
        .thenReturn(payment(order, key, "DONE"));
    service.recover(order.orderId());
    assertThat(deposits(order)).isEqualTo(1);
  }

  @Test
  void staleLeaseCannotCommitAndWebhookDuringLookupIsRetained() {
    var order = order();
    String key = key();
    var old = transactions.prepare(order.orderId(), 20L, confirmRequest(key));
    assertThatThrownBy(() -> transactions.prepare(order.orderId(), 20L, confirmRequest(key)))
        .isInstanceOf(BusinessException.class);
    jdbc.update(
        "UPDATE payment_orders SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND)"
            + " WHERE id=?",
        order.orderId());
    var current = transactions.claimRecovery(order.orderId());
    transactions.finish(old, payment(order, key, "DONE"));
    assertThat(deposits(order)).isZero();
    service.receiveWebhook(
        new TossWebhook("PAYMENT_STATUS_CHANGED", new TossWebhook.Data(order.orderId(), key)));
    transactions.finish(current, payment(order, key, "DONE"));
    assertThat(deposits(order)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT next_check_at IS NOT NULL FROM payment_orders WHERE id=?",
                Boolean.class,
                order.orderId()))
        .isTrue();
  }

  @Test
  void mismatchedProviderResultCannotCreateFunds() {
    var order = order();
    String key = key();
    when(toss.confirm(order.orderId(), key, order.amount()))
        .thenReturn(payment(order, "different-key", "DONE"));
    assertThatThrownBy(() -> service.confirm(order.orderId(), 20L, confirmRequest(key)))
        .isInstanceOf(BusinessException.class);
    assertThat(deposits(order)).isZero();
    assertThat(service.getOrder(order.orderId(), 20L).status()).isEqualTo("CONFIRMING");
  }

  @Test
  void unsupportedMethodOrPartialFundsNeverBecomeAvailable() {
    var order = order();
    String key = key();
    when(toss.confirm(order.orderId(), key, order.amount()))
        .thenReturn(
            new TossPayment(
                key,
                order.orderId(),
                "DONE",
                "KRW",
                "가상계좌",
                order.amount(),
                order.amount(),
                OffsetDateTime.now()));
    assertThat(service.confirm(order.orderId(), 20L, confirmRequest(key)).status())
        .isEqualTo("REVIEW_REQUIRED");
    assertThat(deposits(order)).isZero();
  }

  @Test
  void onlyVerifiedTerminalFailureAllowsNewAttempt() {
    var order = order();
    String key = key();
    when(toss.confirm(order.orderId(), key, order.amount()))
        .thenReturn(payment(order, key, "ABORTED"));
    assertThat(service.confirm(order.orderId(), 20L, confirmRequest(key)).status())
        .isEqualTo("FAILED");
    assertThat(service.create(key(), request(order.jobPostId(), 1)).orderId())
        .isNotEqualTo(order.orderId());
  }

  @Test
  void duplicateAndForgedWebhooksCannotCreditOrBindUnknownKeys() {
    var order = order();
    String key = key();
    service.receiveWebhook(
        new TossWebhook("PAYMENT_STATUS_CHANGED", new TossWebhook.Data(order.orderId(), key)));
    verifyNoInteractions(toss);
    assertThat(orders.findById(order.orderId()).orElseThrow().paymentKey()).isNull();
    approve(order, key);
    when(toss.getPayment(key)).thenReturn(payment(order, key, "DONE"));
    for (int i = 0; i < 2; i++) {
      service.receiveWebhook(
          new TossWebhook("PAYMENT_STATUS_CHANGED", new TossWebhook.Data(order.orderId(), key)));
      service.recover(order.orderId());
    }
    assertThat(deposits(order)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_order_histories WHERE order_id=? AND"
                    + " status='DEPOSITED'",
                Long.class,
                order.orderId()))
        .isEqualTo(1);
  }

  @Test
  void verifiedCancellationBlocksNewLocksAndSupersedesPendingFundingNotification() {
    var order = order();
    String key = key();
    approve(order, key);
    // Isolate this order's notification from pending notifications produced by other tests.
    jdbc.update(
        "UPDATE payment_funding_notifications SET"
            + " next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY) WHERE order_id<>?",
        order.orderId());
    var old = notifications.claim();
    assertThat(old.orderId()).isEqualTo(order.orderId());
    when(toss.getPayment(key)).thenReturn(payment(order, key, "PARTIAL_CANCELED"));
    service.recover(order.orderId());
    notifications.acknowledge(old);
    assertThat(service.getOrder(order.orderId(), 20L).status()).isEqualTo("REVIEW_REQUIRED");
    assertThatThrownBy(
            () ->
                locks.lock(
                    key(),
                    new PaymentLockRequest(
                        IDS.incrementAndGet(), order.jobPostId(), 20L, 30L, BigDecimal.ONE, "KRW")))
        .isInstanceOf(BusinessException.class);
    var latest = notifications.claim();
    assertThat(latest.funded()).isFalse();
    assertThat(latest.revision()).isEqualTo(2);
    assertThat(latest.commandId()).isNotEqualTo(old.commandId());
  }

  @Test
  void fundingNotificationRetriesSameCommandAfterLostAcknowledgement() {
    var order = order();
    approve(order, key());
    jdbc.update(
        "UPDATE payment_funding_notifications SET"
            + " next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY) WHERE order_id<>?",
        order.orderId());
    var first = notifications.claim();
    notifications.retry(first);
    jdbc.update(
        "UPDATE payment_funding_notifications SET next_attempt_at=CURRENT_TIMESTAMP(6) WHERE"
            + " order_id=?",
        order.orderId());
    var retry = notifications.claim();
    assertThat(retry.commandId()).isEqualTo(first.commandId());
    notifications.acknowledge(retry);
    assertThat(
            jdbc.queryForObject(
                "SELECT delivered FROM payment_funding_notifications WHERE order_id=?",
                Boolean.class,
                order.orderId()))
        .isTrue();
  }

  @Test
  void scheduledDeliveryKeepsFundsAndRetriesAfterJobFailure() {
    var order = order();
    approve(order, key());
    jdbc.update(
        "UPDATE payment_funding_notifications SET"
            + " next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY) WHERE order_id<>?",
        order.orderId());
    jdbc.update("UPDATE payment_orders SET next_check_at=NULL");
    doThrow(new IllegalStateException("job unavailable")).doNothing().when(job).deliver(any());
    recovery.recover();
    assertThat(deposits(order)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT delivered FROM payment_funding_notifications WHERE order_id=?",
                Boolean.class,
                order.orderId()))
        .isFalse();
    jdbc.update(
        "UPDATE payment_funding_notifications SET next_attempt_at=CURRENT_TIMESTAMP(6) WHERE"
            + " order_id=?",
        order.orderId());
    recovery.recover();
    var captured =
        org.mockito.ArgumentCaptor.forClass(FundingNotificationRepository.Notification.class);
    verify(job, times(2)).deliver(captured.capture());
    assertThat(captured.getAllValues().get(0).commandId())
        .isEqualTo(captured.getAllValues().get(1).commandId());
    assertThat(
            jdbc.queryForObject(
                "SELECT delivered FROM payment_funding_notifications WHERE order_id=?",
                Boolean.class,
                order.orderId()))
        .isTrue();
  }

  @Test
  void concurrentOrderCreationUsesOneCommandAndOneOrder() throws Exception {
    String key = key();
    var request = request(IDS.incrementAndGet(), 1);
    var pool = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      Callable<OrderResponse> call =
          () -> {
            start.await();
            return service.create(key, request);
          };
      var first = pool.submit(call);
      var second = pool.submit(call);
      start.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void jwtOwnershipInternalAuthAndWebhookRoutes() throws Exception {
    var order = order();
    String path = "/api/payments/orders/" + order.orderId();
    mvc.perform(get(path)).andExpect(status().isUnauthorized());
    mvc.perform(get(path).header("Authorization", "Bearer invalid"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get(path).header("Authorization", "Bearer " + token(20, "WORKER")))
        .andExpect(status().isForbidden());
    mvc.perform(get(path).header("Authorization", "Bearer " + token(21, "OWNER")))
        .andExpect(status().isNotFound());
    mvc.perform(get(path).header("Authorization", "Bearer " + token(20, "OWNER")))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/payments/internal/orders")
                .header("Authorization", "Bearer " + token(20, "OWNER")))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/payments/webhooks/toss")
                .contentType("application/json")
                .content(
                    """
                    {"eventType":"PAYMENT_STATUS_CHANGED","data":{"orderId":"%s","paymentKey":"forged-key","status":"DONE"}}
                    """
                        .formatted(order.orderId())))
        .andExpect(status().isOk());
    assertThat(deposits(order)).isZero();
  }

  @Test
  void httpRejectsManipulatedApprovalAmountBeforeProviderCall() throws Exception {
    var order = order();
    mvc.perform(
            post("/api/payments/orders/" + order.orderId() + "/confirm")
                .header("Authorization", "Bearer " + token(20, "OWNER"))
                .contentType("application/json")
                .content("{\"paymentKey\":\"payment-key\",\"amount\":100}"))
        .andExpect(status().isConflict());
    verifyNoInteractions(toss);
  }

  String token(long member, String role) throws Exception {
    var b64 = Base64.getUrlEncoder().withoutPadding();
    String header =
        b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String body =
        b64.encodeToString(
            ("{\"authAccountId\":1,\"memberId\":"
                    + member
                    + ",\"role\":\""
                    + role
                    + "\",\"exp\":"
                    + (Instant.now().getEpochSecond() + 60)
                    + "}")
                .getBytes(StandardCharsets.UTF_8));
    String unsigned = header + "." + body;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(
        new SecretKeySpec(
            "payment-test-jwt-secret-with-at-least-32-bytes".getBytes(StandardCharsets.UTF_8),
            "HmacSHA256"));
    return unsigned
        + "."
        + b64.encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
  }
}
