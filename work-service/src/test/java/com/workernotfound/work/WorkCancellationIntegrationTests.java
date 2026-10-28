package com.workernotfound.work;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.work.domain.outbox.WorkOutboxRepository;
import com.workernotfound.work.domain.work.dto.request.*;
import com.workernotfound.work.domain.work.dto.response.WorkCancellationResponse;
import com.workernotfound.work.domain.work.entity.enums.WorkCancellationStatus;
import com.workernotfound.work.domain.work.event.MatchConfirmedEvent;
import com.workernotfound.work.domain.work.service.*;
import com.workernotfound.work.global.exception.BusinessException;
import com.workernotfound.work.global.security.AuthenticatedMember;
import com.workernotfound.work.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class WorkCancellationIntegrationTests extends IntegrationTestSupport {
  static final AtomicLong IDS = new AtomicLong(2_000_000);
  static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
  @Autowired WorkApplicationService works;
  @Autowired WorkConfirmationService confirmations;
  @Autowired WorkAttendanceService attendance;
  @Autowired WorkCancellationApplicationService cancellations;
  @Autowired WorkCancellationExpiryService expiry;
  @Autowired WorkCancellationExpiryScheduler scheduler;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired PlatformTransactionManager transactions;
  @Autowired com.workernotfound.work.domain.outbox.WorkOutboxRelay relay;
  @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
  @MockitoBean Clock clock;
  @MockitoSpyBean WorkOutboxRepository outbox;
  final AtomicReference<Instant> instant = new AtomicReference<>();
  ScheduledWorkRequest schedule;
  long workId;

  @BeforeEach
  void setup() {
    at("2020-10-28T16:00:00");
    when(clock.instant()).thenAnswer(call -> instant.get());
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    long id = IDS.addAndGet(10);
    schedule = new ScheduledWorkRequest(id, id + 1, id + 2, id + 3, "payment-" + id,
        LocalDate.of(2020, 10, 28), LocalTime.of(17, 0), LocalTime.of(23, 0), false,
        new BigDecimal("37.5"), new BigDecimal("127"));
    workId = Long.parseLong(works.create(key(), schedule).workId());
    confirmations.confirm(new MatchConfirmedEvent(key(), "MatchConfirmed", LocalDateTime.of(2020, 10, 28, 15, 0),
        id, 1L, 1, id, String.valueOf(workId), schedule.jobPostId(), schedule.ownerMemberId(), schedule.workerMemberId(),
        schedule.paymentId(), schedule.workDate(), schedule.startTime(), schedule.endTime()));
  }

  String key() { return UUID.randomUUID().toString(); }
  void at(String value) { instant.set(LocalDateTime.parse(value).atZone(ZONE).toInstant()); }
  AuthenticatedMember owner() { return new AuthenticatedMember(1L, schedule.ownerMemberId(), "OWNER"); }
  AuthenticatedMember worker() { return new AuthenticatedMember(2L, schedule.workerMemberId(), "WORKER"); }
  String workStatus() { return jdbc.queryForObject("SELECT status FROM works WHERE id = ?", String.class, workId); }
  WorkCancellationResponse request() { return cancellations.request(owner(), workId, key(), new CancellationRequest("일정 변경 요청")); }
  long count(String type) { return jdbc.queryForObject("SELECT COUNT(*) FROM work_outbox WHERE work_id = ? AND event_type = ?", Long.class, workId, type); }
  void checkIn() { attendance.checkIn(worker(), workId, new CheckInRequest(schedule.latitude(), schedule.longitude())); }

  @Test
  void requestEventsHaveTheirOwnAggregateAndRevisionInRedis() {
    when(clock.getZone()).thenReturn(ZONE);
    var first = request();
    cancellations.respond(worker(), workId, first.requestId(), false);
    var second = request();
    cancellations.withdraw(owner(), workId, second.requestId());
    relay.relay();
    var records = redis.opsForStream().range("work:domain-events", org.springframework.data.domain.Range.unbounded());
    assertThat(records).isNotNull();
    var requestEvents = records.stream().filter(record -> Set.of(first.requestId().toString(), second.requestId().toString())
        .contains(record.getValue().get("aggregateId")) && "WORK_CANCELLATION".equals(record.getValue().get("aggregateType"))).toList();
    assertThat(requestEvents).hasSize(4);
    assertThat(count("WorkCanceled")).isZero();
    assertThat(workStatus()).isEqualTo("SCHEDULED");
  }

  @Test
  void cancellationRequiresOtherParticipantAndReplaysApprovalAfterDeadline() {
    var request = request();
    assertThat(workStatus()).isEqualTo("SCHEDULED");
    assertThatThrownBy(() -> cancellations.respond(owner(), workId, request.requestId(), true)).isInstanceOf(BusinessException.class);
    var approved = cancellations.respond(worker(), workId, request.requestId(), true);
    assertThat(approved.status()).isEqualTo(WorkCancellationStatus.APPROVED);
    assertThat(approved.requesterMemberId()).isEqualTo(owner().memberId());
    assertThat(approved.decisionMemberId()).isEqualTo(worker().memberId());
    assertThat(workStatus()).isEqualTo("CANCELED");
    at("2020-10-28T18:00:00");
    assertThat(cancellations.respond(worker(), workId, request.requestId(), true)).isEqualTo(approved);
    assertThat(count("WorkCanceled")).isEqualTo(1);
    assertThat(count("WorkCancellationRequested")).isEqualTo(1);
  }

  @Test
  void idempotentRequestsPreserveReasonAndRejectedRequestsAllowNewAttempt() {
    String key = key();
    var first = cancellations.request(owner(), workId, key, new CancellationRequest(" 사유 "));
    assertThat(cancellations.request(owner(), workId, key, new CancellationRequest("사유"))).isEqualTo(first);
    assertThatThrownBy(() -> cancellations.request(owner(), workId, key, new CancellationRequest("다른 사유"))).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> cancellations.request(worker(), workId, key(), new CancellationRequest("다른 요청"))).isInstanceOf(BusinessException.class);
    assertThat(cancellations.respond(worker(), workId, first.requestId(), false).status()).isEqualTo(WorkCancellationStatus.REJECTED);
    assertThat(workStatus()).isEqualTo("SCHEDULED");
    var next = request();
    assertThat(next.requestId()).isNotEqualTo(first.requestId());
    assertThat(cancellations.withdraw(owner(), workId, next.requestId()).status()).isEqualTo(WorkCancellationStatus.WITHDRAWN);
    assertThat(count("WorkCanceled")).isZero();
  }

  @Test
  void deadlineRejectionCommitsExpiryRatherThanRollingItBack() {
    var request = request();
    at("2020-10-28T17:00:00");
    assertThatThrownBy(() -> cancellations.respond(worker(), workId, request.requestId(), true)).isInstanceOf(BusinessException.class);
    assertThat(cancellations.getRequests(owner(), workId).get(0).status()).isEqualTo(WorkCancellationStatus.EXPIRED);
    assertThat(workStatus()).isEqualTo("SCHEDULED");
    expiry.expire(workId);
    assertThat(count("WorkCancellationExpired")).isEqualTo(1);
    assertThatThrownBy(this::request).isInstanceOf(BusinessException.class);
  }

  @Test
  void checkInIsAllowedWhileWaitingAndExpiresTheRequestAtomically() {
    request();
    at("2020-10-28T16:30:00");
    checkIn();
    assertThat(workStatus()).isEqualTo("CHECKED_IN");
    assertThat(cancellations.getRequests(worker(), workId).get(0).status()).isEqualTo(WorkCancellationStatus.EXPIRED);
    assertThat(count("WorkCheckedIn")).isEqualTo(1);
    assertThat(count("WorkCancellationExpired")).isEqualTo(1);
  }

  @Test
  void schedulerExpiresPendingRequestAtTheStartTimeOnlyOnce() {
    request();
    scheduler.expire();
    assertThat(cancellations.getRequests(owner(), workId).get(0).status()).isEqualTo(WorkCancellationStatus.PENDING);
    at("2020-10-28T17:00:00");
    scheduler.expire(); scheduler.expire();
    assertThat(cancellations.getRequests(owner(), workId).get(0).status()).isEqualTo(WorkCancellationStatus.EXPIRED);
    assertThat(count("WorkCancellationExpired")).isEqualTo(1);
  }

  @Test
  void approvalOutboxFailureRollsBackWorkAndConsent() {
    var request = request();
    new TransactionTemplate(transactions).executeWithoutResult(tx ->
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("injected"))
            .when(outbox).append(any(), anyString()));
    assertThatThrownBy(() -> cancellations.respond(worker(), workId, request.requestId(), true))
        .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    assertThat(workStatus()).isEqualTo("SCHEDULED");
    assertThat(cancellations.getRequests(owner(), workId).get(0).status()).isEqualTo(WorkCancellationStatus.PENDING);
    assertThat(count("WorkCanceled")).isZero();
  }

  @Test
  void approvalAndCheckInCannotBothWin() throws Exception {
    var request = request();
    at("2020-10-28T16:30:00");
    var executor = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      var approve = executor.submit(() -> { start.await(); try { cancellations.respond(worker(), workId, request.requestId(), true); } catch (BusinessException ignored) {} return true; });
      var checkIn = executor.submit(() -> { start.await(); try { checkIn(); } catch (BusinessException ignored) {} return true; });
      start.countDown(); approve.get(10, TimeUnit.SECONDS); checkIn.get(10, TimeUnit.SECONDS);
      var result = cancellations.getRequests(owner(), workId).get(0).status();
      if (workStatus().equals("CANCELED")) {
        assertThat(result).isEqualTo(WorkCancellationStatus.APPROVED);
        assertThat(count("WorkCheckedIn")).isZero();
      } else {
        assertThat(workStatus()).isEqualTo("CHECKED_IN");
        assertThat(result).isEqualTo(WorkCancellationStatus.EXPIRED);
        assertThat(count("WorkCanceled")).isZero();
      }
    } finally { executor.shutdownNow(); }
  }

  String token(AuthenticatedMember member) throws Exception {
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String payload = encoder.encodeToString(mapper.writeValueAsBytes(Map.of("authAccountId", member.authAccountId(),
        "memberId", member.memberId(), "role", member.role(), "exp", Instant.now().plusSeconds(300).getEpochSecond())));
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("work-test-jwt-secret-for-integration-only".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "Bearer " + header + "." + payload + "." + encoder.encodeToString(mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void httpValidatesInputAndRestrictsRequestsToParticipants() throws Exception {
    String path = "/api/works/me/" + workId + "/cancellations";
    mvc.perform(post(path).contentType("application/json").content("{\"reason\":\"취소\"}")).andExpect(status().isUnauthorized());
    mvc.perform(post(path).header("Authorization", token(owner())).header("Idempotency-Key", key())
        .contentType("application/json").content("{\"reason\":\" \"}")).andExpect(status().isBadRequest());
    mvc.perform(post(path).header("Authorization", token(owner())).header("Idempotency-Key", "invalid key")
        .contentType("application/json").content("{\"reason\":\"취소\"}")).andExpect(status().isBadRequest());
    mvc.perform(get(path).header("Authorization", token(new AuthenticatedMember(4L, 99L, "WORKER"))))
        .andExpect(status().isNotFound());
    var request = request();
    mvc.perform(post(path + "/" + request.requestId() + "/approve").header("Authorization", token(owner())))
        .andExpect(status().isNotFound());
    mvc.perform(post(path + "/" + request.requestId() + "/approve").header("Authorization", token(worker())))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"));
  }
}
