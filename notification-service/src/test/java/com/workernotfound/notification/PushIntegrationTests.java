package com.workernotfound.notification;

import com.workernotfound.notification.domain.push.dto.request.DeviceTokenRequest;
import com.workernotfound.notification.domain.push.dto.response.DeviceTokenResponse;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.notification.domain.notification.event.NotificationEvent;
import com.workernotfound.notification.domain.notification.service.*;
import com.workernotfound.notification.domain.push.service.*;
import com.workernotfound.notification.global.account.AccountGateService;
import com.workernotfound.notification.global.exception.BusinessException;
import com.workernotfound.notification.global.security.AuthenticatedMember;
import com.workernotfound.notification.support.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class PushIntegrationTests extends IntegrationTestSupport {
  private static final String PATH = "/api/notifications/me/device-tokens";
  private static final AtomicLong IDS = new AtomicLong(2000000);
  @Autowired JdbcTemplate jdbc;
  @Autowired DeviceTokenService devices;
  @Autowired PushDeliveryStore deliveries;
  @Autowired NotificationEventService events;
  @Autowired NotificationService notifications;
  @Autowired FundingIncidentService funding;
  @Autowired WorkNotificationEventService work;
  @Autowired AccountGateService gates;
  @Autowired PlatformTransactionManager transactions;
  @Autowired ObjectMapper mapper;
  @Autowired MockMvc mvc;

  @BeforeEach void clearPushFixtures() {
    jdbc.update("DELETE FROM notification_push_deliveries");
    jdbc.update("DELETE FROM notification_device_tokens");
  }

  @Test void apiRequiresJwtValidatesTokensAndScopesRemovalToMemberAndRole() throws Exception {
    long id = IDS.incrementAndGet();
    String auth = jwt(id, "WORKER");
    String body = "{\"token\":\"test-fcm-token\",\"platform\":\"ANDROID\"}";
    mvc.perform(put(PATH).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
    mvc.perform(delete(PATH + "/1")).andExpect(status().isUnauthorized());
    mvc.perform(put(PATH).header("Authorization", auth).contentType("application/json")
        .content(body.replace("ANDROID", "IOS"))).andExpect(status().isBadRequest());
    mvc.perform(put(PATH).header("Authorization", auth).contentType("application/json")
        .content(body.replace("test-fcm-token", "bad token"))).andExpect(status().isBadRequest());
    var response = mvc.perform(put(PATH).header("Authorization", auth).contentType("application/json").content(body))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.token").doesNotExist()).andReturn();
    long deviceId = mapper.readTree(response.getResponse().getContentAsString()).path("data").path("deviceTokenId").asLong();
    mvc.perform(put(PATH).header("Authorization", auth).contentType("application/json").content(body))
        .andExpect(jsonPath("$.data.deviceTokenId").value(deviceId));
    mvc.perform(delete(PATH + "/" + deviceId).header("Authorization", jwt(id + 1, "WORKER"))).andExpect(status().isOk());
    mvc.perform(delete(PATH + "/" + deviceId).header("Authorization", jwt(id, "OWNER"))).andExpect(status().isOk());
    assertThat(active(deviceId)).isTrue();
    mvc.perform(delete(PATH + "/" + deviceId).header("Authorization", auth)).andExpect(status().isOk());
    assertThat(active(deviceId)).isFalse();
    assertThat(jdbc.queryForObject("SELECT token FROM notification_device_tokens WHERE id=?", String.class, deviceId)).isEmpty();
    mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['" + PATH + "'].put").exists());
  }

  @Test void notificationAndPushCommandsCommitTogetherAndRedeliveryDoesNotDuplicate() {
    var event = event();
    devices.register(member(event.ownerMemberId(), "OWNER"), request("owner-token"));
    devices.register(member(event.workerMemberId(), "WORKER"), request("worker-first"));
    devices.register(member(event.workerMemberId(), "WORKER"), request("worker-second"));
    new TransactionTemplate(transactions).executeWithoutResult(tx -> { events.receive(event); tx.setRollbackOnly(); });
    assertThat(count()).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE event_id=?", Long.class, event.eventId())).isZero();
    events.receive(event);
    events.receive(event);
    assertThat(count()).isEqualTo(3);
    assertThat(notifications.getUnreadCount(member(event.workerMemberId(), "WORKER")).unreadCount()).isEqualTo(1);
  }

  @Test void externalFailureDoesNotRollbackInboxAndRetriesThenStopsAfterSuccess() {
    var event = queued();
    var calls = new AtomicLong();
    var dispatcher = new PushDispatcher(deliveries, dispatch -> {
      if (calls.incrementAndGet() == 1) throw new IllegalStateException("sensitive remote detail");
      return accepted();
    });
    dispatcher.dispatch();
    assertThat(state()).isEqualTo("PENDING");
    assertThat(notifications.getUnreadCount(member(event.workerMemberId(), "WORKER")).unreadCount()).isEqualTo(1);
    ready();
    dispatcher.dispatch();
    dispatcher.dispatch();
    assertThat(state()).isEqualTo("SENT");
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test void leasesPreventConcurrentClaimsAndFenceStaleCompletionAndInvalidation() throws Exception {
    queued();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> deliveries.claim());
      var second = executor.submit(() -> deliveries.claim());
      var claimed = new ArrayList<PushDispatch>();
      first.get(10, TimeUnit.SECONDS).ifPresent(claimed::add);
      second.get(10, TimeUnit.SECONDS).ifPresent(claimed::add);
      assertThat(claimed).hasSize(1);
      PushDispatch original = claimed.get(0);
      jdbc.update("UPDATE notification_push_deliveries SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND)");
      PushDispatch recovered = deliveries.claim().orElseThrow();
      deliveries.complete(original, new PushResult(PushResult.Outcome.INVALID_TOKEN, "UNREGISTERED", null));
      assertThat(state()).isEqualTo("SENDING");
      assertThat(active(recovered.deviceTokenId())).isTrue();
      deliveries.complete(recovered, accepted());
      assertThat(state()).isEqualTo("SENT");
      assertThat(recovered.attempt()).isEqualTo(2);
    } finally { executor.shutdownNow(); }
  }

  @Test void accountTransferAndLogoutSuppressOldRecipientDeliveries() {
    var event = queued();
    long deviceId = deviceId();
    devices.register(member(event.workerMemberId(), "OWNER"), request("queued-device"));
    assertThat(deliveries.claim()).isEmpty();
    assertThat(state()).isEqualTo("CANCELED");
    assertThat(active(deviceId)).isTrue();
    clearPushFixtures();
    event = queued();
    devices.remove(member(event.workerMemberId(), "WORKER"), deviceId());
    assertThat(deliveries.claim()).isEmpty();
    assertThat(state()).isEqualTo("CANCELED");
  }

  @Test void lateInvalidTokenResultCannotDeleteTheNewOwnersRegistration() {
    queued();
    PushDispatch claimed = deliveries.claim().orElseThrow();
    long newOwner = IDS.incrementAndGet();
    devices.register(member(newOwner, "WORKER"), request("queued-device"));
    deliveries.complete(claimed, new PushResult(PushResult.Outcome.INVALID_TOKEN, "UNREGISTERED", null));
    assertThat(active(claimed.deviceTokenId())).isTrue();
    assertThat(jdbc.queryForObject("SELECT member_id FROM notification_device_tokens WHERE id=?", Long.class,
        claimed.deviceTokenId())).isEqualTo(newOwner);
  }

  @Test void unregisteredTokensAreClearedAndReRegistrationDoesNotReplayOldJobs() {
    var event = queued();
    PushDispatch claimed = deliveries.claim().orElseThrow();
    deliveries.complete(claimed, new PushResult(PushResult.Outcome.INVALID_TOKEN, "UNREGISTERED", null));
    assertThat(active(claimed.deviceTokenId())).isFalse();
    assertThat(state()).isEqualTo("FAILED");
    devices.register(member(event.workerMemberId(), "WORKER"), request("queued-device"));
    assertThat(deliveries.claim()).isEmpty();
    assertThat(active(claimed.deviceTokenId())).isTrue();
  }

  @Test void withdrawalClearsTokensBlocksRegistrationAndSuppressesPendingPush() {
    var event = queued();
    String command = UUID.randomUUID().toString();
    gates.transition(event.workerMemberId(), command, "prepare");
    gates.transition(event.workerMemberId(), command, "commit");
    assertThat(active(deviceId())).isFalse();
    assertThatThrownBy(() -> devices.register(member(event.workerMemberId(), "WORKER"), request("new-token")))
        .isInstanceOf(BusinessException.class);
    assertThat(deliveries.claim()).isEmpty();
    assertThat(state()).isEqualTo("CANCELED");
  }

  @Test void expirationAndRetryBudgetBoundTheQueue() {
    queued();
    jdbc.update("UPDATE notification_push_deliveries SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND)");
    assertThat(deliveries.claim()).isEmpty();
    assertThat(state()).isEqualTo("CANCELED");
    clearPushFixtures();
    queued();
    for (int attempt = 0; attempt < 8; attempt++) {
      ready();
      deliveries.complete(deliveries.claim().orElseThrow(), new PushResult(PushResult.Outcome.RETRY, "UNAVAILABLE", Duration.ofSeconds(60)));
    }
    assertThat(state()).isEqualTo("FAILED");
    assertThat(deliveries.claim()).isEmpty();
  }

  @Test void allExistingNotificationSourcesProducePushCommands() {
    var source = new WorkNotificationIntegrationTests().event("WorkStarted", "OWNER");
    devices.register(member(source.workerMemberId(), "WORKER"), request("work-device"));
    work.receive(source);
    work.receive(source);
    long owner = IDS.incrementAndGet();
    devices.register(member(owner, "OWNER"), request("funding-device"));
    String key = UUID.randomUUID().toString();
    var command = new FundingIncidentService.Command(1L, owner, "OWNER", null, null, LocalDateTime.now());
    funding.receive(key, command);
    funding.receive(key, command);
    assertThat(count()).isEqualTo(2);
  }

  @Test void parallelRegistrationKeepsOneTokenBindingAndOneDelivery() throws Exception {
    var event = event();
    var executor = Executors.newFixedThreadPool(4);
    try {
      var futures = new ArrayList<Future<DeviceTokenResponse>>();
      for (int i = 0; i < 8; i++) futures.add(executor.submit(() ->
          devices.register(member(event.workerMemberId(), "WORKER"), request("one-token"))));
      var ids = new HashSet<Long>();
      for (var future : futures) ids.add(future.get(10, TimeUnit.SECONDS).deviceTokenId());
      assertThat(ids).hasSize(1);
      events.receive(event);
      assertThat(count()).isEqualTo(1);
    } finally { executor.shutdownNow(); }
  }

  private NotificationEvent queued() {
    var event = event();
    devices.register(member(event.workerMemberId(), "WORKER"), request("queued-device"));
    events.receive(event);
    return event;
  }

  private NotificationEvent event() {
    long id = IDS.addAndGet(10);
    return new NotificationEvent(UUID.randomUUID().toString(), "MatchConfirmed", LocalDateTime.now(),
        id, 1L, 1, id, id + 1, id + 2, id + 3, id + 4, null);
  }

  private AuthenticatedMember member(long id, String role) { return new AuthenticatedMember(id, id, role); }
  private DeviceTokenRequest request(String token) { return new DeviceTokenRequest(token, "ANDROID"); }
  private PushResult accepted() { return new PushResult(PushResult.Outcome.ACCEPTED, null, null); }
  private long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM notification_push_deliveries", Long.class); }
  private String state() { return jdbc.queryForObject("SELECT state FROM notification_push_deliveries", String.class); }
  private long deviceId() { return jdbc.queryForObject("SELECT id FROM notification_device_tokens", Long.class); }
  private boolean active(long id) { return jdbc.queryForObject("SELECT active FROM notification_device_tokens WHERE id=?", Boolean.class, id); }
  private void ready() { jdbc.update("UPDATE notification_push_deliveries SET next_attempt_at=UTC_TIMESTAMP(6) WHERE state='PENDING'"); }

  private String jwt(long id, String role) throws Exception {
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String payload = mapper.writeValueAsString(Map.of("authAccountId", id, "memberId", id, "role", role,
        "exp", Instant.now().plusSeconds(300).getEpochSecond()));
    String unsigned = header + "." + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("notification-test-jwt-secret-for-integration-only".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "Bearer " + unsigned + "." + encoder.encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
  }
}
