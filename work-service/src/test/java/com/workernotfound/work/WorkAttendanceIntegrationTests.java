package com.workernotfound.work;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.work.domain.work.dto.request.*;
import com.workernotfound.work.domain.work.event.MatchConfirmedEvent;
import com.workernotfound.work.domain.work.repository.WorkAttendanceHistoryRepository;
import com.workernotfound.work.domain.work.policy.*;
import com.workernotfound.work.domain.work.service.*;
import com.workernotfound.work.global.exception.BusinessException;
import com.workernotfound.work.global.security.AuthenticatedMember;
import com.workernotfound.work.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

@AutoConfigureMockMvc
class WorkAttendanceIntegrationTests extends IntegrationTestSupport {
  static final AtomicLong IDS = new AtomicLong(200000);
  static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
  @Autowired WorkApplicationService works;
  @Autowired WorkConfirmationService confirmations;
  @Autowired WorkAttendanceService attendance;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @MockitoBean Clock clock;
  @MockitoSpyBean WorkAttendanceHistoryRepository history;
  @MockitoSpyBean AttendancePolicy policy;
  AtomicReference<Instant> instant = new AtomicReference<>();

  @BeforeEach
  void setClock() {
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    when(clock.instant()).thenAnswer(call -> instant.get());
    at("2026-10-04T09:00:00");
  }

  void at(String dateTime) { instant.set(LocalDateTime.parse(dateTime).atZone(ZONE).toInstant()); }

  ScheduledWorkRequest request(boolean location, boolean overnight) {
    long id = IDS.addAndGet(10);
    return new ScheduledWorkRequest(id, id + 1, id + 2, id + 3, "payment-" + id,
        LocalDate.of(2026, 10, 4), LocalTime.of(overnight ? 23 : 9, 0), LocalTime.of(overnight ? 2 : 18, 0), null,
        location ? new BigDecimal("37.5") : null, location ? new BigDecimal("127.0") : null);
  }

  Long create(ScheduledWorkRequest request, boolean confirmed) {
    String id = works.create(UUID.randomUUID().toString(), request).workId();
    if (confirmed) confirmations.confirm(new MatchConfirmedEvent(UUID.randomUUID().toString(), "MatchConfirmed",
        LocalDateTime.of(2026, 10, 3, 12, 0), request.matchingId(), 1L, 1, request.matchingId(), id,
        request.jobPostId(), request.ownerMemberId(), request.workerMemberId(), request.paymentId(),
        request.workDate(), request.startTime(), request.endTime()));
    return Long.valueOf(id);
  }

  AuthenticatedMember owner(ScheduledWorkRequest request) {
    return new AuthenticatedMember(request.ownerMemberId(), request.ownerMemberId(), "OWNER");
  }

  AuthenticatedMember worker(ScheduledWorkRequest request) {
    return new AuthenticatedMember(request.workerMemberId(), request.workerMemberId(), "WORKER");
  }

  CheckInRequest gps() { return new CheckInRequest(new BigDecimal("37.5"), new BigDecimal("127.0")); }

  String token(long memberId, String role) throws Exception {
    return token(memberId, role, Instant.now().plusSeconds(300).getEpochSecond());
  }

  String token(long memberId, String role, long expiry) throws Exception {
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String header =
        encoder.encodeToString(
            "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String payload =
        mapper.writeValueAsString(
            Map.of("authAccountId", memberId, "memberId", memberId, "role", role, "exp", expiry));
    String unsigned =
        header + "." + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(
        new SecretKeySpec(
            "work-test-jwt-secret-for-integration-only".getBytes(StandardCharsets.UTF_8),
            "HmacSHA256"));
    return "Bearer "
        + unsigned
        + "."
        + encoder.encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
  }


  @Test
  void workerCheckInThenOwnerStartAndCompletionPreserveFirstTimesAndHistory() throws Exception {
    var request = request(true, false);
    Long id = create(request, true);
    mvc.perform(post("/api/works/me/" + id + "/check-in")
        .header("Authorization", token(request.workerMemberId(), "WORKER"))
        .contentType("application/json").content(mapper.writeValueAsString(gps())))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CHECKED_IN"));
    var first = attendance.checkIn(worker(request), id, gps());
    assertThat(first.checkedInAt()).isEqualTo(LocalDateTime.of(2026, 10, 4, 9, 0));
    assertThatThrownBy(() -> attendance.complete(owner(request), id)).isInstanceOf(BusinessException.class);
    mvc.perform(post("/api/works/me/" + id + "/start")
        .header("Authorization", token(request.ownerMemberId(), "OWNER")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
    at("2026-10-04T18:00:00");
    mvc.perform(post("/api/works/me/" + id + "/complete")
        .header("Authorization", token(request.ownerMemberId(), "OWNER")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED"));
    var completed = attendance.complete(owner(request), id);
    at("2026-10-04T19:00:00");
    assertThat(attendance.complete(owner(request), id).completedAt()).isEqualTo(completed.completedAt());
    assertThat(attendance.start(owner(request), id).startedAt()).isEqualTo(first.checkedInAt());
    assertThat(attendance.checkIn(worker(request), id, gps()).checkedInAt()).isEqualTo(first.checkedInAt());
    assertThat(jdbc.queryForList("SELECT next_status FROM work_status_histories WHERE work_id = ? ORDER BY id",
        String.class, id)).containsExactly("SCHEDULED", "CHECKED_IN", "IN_PROGRESS", "COMPLETED");
    assertThat(jdbc.queryForObject("SELECT check_in_distance_meters FROM works WHERE id = ?", Double.class, id)).isZero();
    assertThatThrownBy(() -> works.cancel(UUID.randomUUID().toString(), id)).isInstanceOf(BusinessException.class);
  }

  @Test
  void outsideRadiusRejectsWithoutChangingStoredJobLocationOrAttendance() throws Exception {
    var request = request(true, false);
    Long id = create(request, true);
    mvc.perform(post("/api/works/me/" + id + "/check-in")
        .header("Authorization", token(request.workerMemberId(), "WORKER"))
        .contentType("application/json").content("{\"latitude\":37.51,\"longitude\":127.0}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK-409-007"));
    assertThat(jdbc.queryForObject("SELECT latitude FROM works WHERE id = ?", BigDecimal.class, id))
        .isEqualByComparingTo(request.latitude());
    assertThat(jdbc.queryForObject("SELECT longitude FROM works WHERE id = ?", BigDecimal.class, id))
        .isEqualByComparingTo(request.longitude());
    assertThat(jdbc.queryForObject("SELECT status FROM works WHERE id = ?", String.class, id)).isEqualTo("SCHEDULED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_status_histories WHERE work_id = ? AND next_status = 'CHECKED_IN'",
        Long.class, id)).isZero();
    mvc.perform(post("/api/works/me/" + id + "/check-in")
        .header("Authorization", token(request.workerMemberId(), "WORKER"))
        .contentType("application/json").content(mapper.writeValueAsString(gps())))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CHECKED_IN"));
  }

  @Test
  void permissionsRequireConfirmedMatchingAndCorrectParticipantRole() throws Exception {
    var request = request(true, false);
    Long pending = create(request, false);
    String worker = token(request.workerMemberId(), "WORKER");
    mvc.perform(post("/api/works/me/" + pending + "/check-in").header("Authorization", worker)
        .contentType("application/json").content(mapper.writeValueAsString(gps())))
        .andExpect(status().isNotFound());
    var confirmed = request(true, false);
    Long id = create(confirmed, true);
    for (String auth : List.of(token(confirmed.ownerMemberId(), "OWNER"), token(999999, "WORKER")))
      mvc.perform(post("/api/works/me/" + id + "/check-in").header("Authorization", auth)
          .contentType("application/json").content(mapper.writeValueAsString(gps())))
          .andExpect(status().isNotFound());
    for (String operation : List.of("start", "complete"))
      mvc.perform(post("/api/works/me/" + id + "/" + operation)
          .header("Authorization", token(confirmed.workerMemberId(), "WORKER")))
          .andExpect(status().isNotFound());
    mvc.perform(post("/api/works/me/" + id + "/check-in").contentType("application/json")
        .content(mapper.writeValueAsString(gps()))).andExpect(status().isUnauthorized());
  }

  @Test
  void completionAuthorityFollowsConfiguredPolicyWithoutChangingLifecycle() {
    var request = request(true, false);
    Long id = create(request, true);
    attendance.checkIn(worker(request), id, gps());
    attendance.start(owner(request), id);
    at("2026-10-04T18:00:00");
    doReturn(CompletionRole.WORKER).when(policy).completionRole();
    assertThatThrownBy(() -> attendance.complete(owner(request), id)).isInstanceOf(BusinessException.class);
    assertThat(attendance.complete(worker(request), id).completedAt()).isNotNull();
  }

  @Test
  void absentLocationAndInvalidCoordinatesFailClosedAndPolicyIsDiscoverable() throws Exception {
    var request = request(false, false);
    Long id = create(request, true);
    String auth = token(request.workerMemberId(), "WORKER");
    mvc.perform(post("/api/works/me/" + id + "/check-in").header("Authorization", auth)
        .contentType("application/json").content(mapper.writeValueAsString(gps())))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK-409-005"));
    for (String body : List.of("{}", "{\"latitude\":91,\"longitude\":127}", "{\"latitude\":37,\"longitude\":181}"))
      mvc.perform(post("/api/works/me/" + id + "/check-in").header("Authorization", auth)
          .contentType("application/json").content(body)).andExpect(status().isBadRequest());
    mvc.perform(get("/api/works/me/attendance-policy").header("Authorization", auth))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.radiusMeters").value(100))
        .andExpect(jsonPath("$.data.earlyWindowSeconds").value(1800))
        .andExpect(jsonPath("$.data.lateWindowSeconds").value(1800))
        .andExpect(jsonPath("$.data.completionRole").value("OWNER"));
    mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/works/me/{workId}/check-in'].post").exists());
  }

  @Test
  void overnightScheduleAndWindowBoundariesUseConfiguredTimeZoneEvenWithUtcClock() {
    var request = request(true, true);
    Long id = create(request, true);
    at("2026-10-04T22:29:59");
    assertThatThrownBy(() -> attendance.checkIn(worker(request), id, gps())).isInstanceOf(BusinessException.class);
    at("2026-10-04T22:30:00");
    attendance.checkIn(worker(request), id, gps());
    assertThatThrownBy(() -> attendance.start(owner(request), id)).isInstanceOf(BusinessException.class);
    at("2026-10-04T23:00:00");
    attendance.start(owner(request), id);
    at("2026-10-05T01:59:59");
    assertThatThrownBy(() -> attendance.complete(owner(request), id)).isInstanceOf(BusinessException.class);
    at("2026-10-05T02:00:00");
    assertThat(attendance.complete(owner(request), id).status().name()).isEqualTo("COMPLETED");
    var late = request(true, false);
    Long lateId = create(late, true);
    at("2026-10-04T09:30:01");
    assertThatThrownBy(() -> attendance.checkIn(worker(late), lateId, gps())).isInstanceOf(BusinessException.class);
    at("2026-10-04T09:30:00");
    attendance.checkIn(worker(late), lateId, gps());
  }

  @Test
  void concurrentCheckInsOnlyWriteOneHistoryAndHistoryFailureRollsBackState() throws Exception {
    var request = request(true, false);
    Long id = create(request, true);
    var executor = Executors.newFixedThreadPool(6);
    var start = new CountDownLatch(1);
    try {
      var futures = new ArrayList<Future<LocalDateTime>>();
      for (int i = 0; i < 6; i++) futures.add(executor.submit(() -> {
        start.await(); return attendance.checkIn(worker(request), id, gps()).checkedInAt();
      }));
      start.countDown();
      var times = new HashSet<LocalDateTime>();
      for (var future : futures) times.add(future.get(15, TimeUnit.SECONDS));
      assertThat(times).hasSize(1);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_status_histories WHERE work_id = ? AND next_status = 'CHECKED_IN'",
          Long.class, id)).isEqualTo(1);
    } finally { executor.shutdownNow(); }
    var failed = request(true, false);
    Long failedId = create(failed, true);
    doThrow(new org.springframework.dao.DataIntegrityViolationException("simulated history failure"))
        .when(history).record(eq(failedId), anyString(), anyString(), anyLong(), any());
    assertThatThrownBy(() -> attendance.checkIn(worker(failed), failedId, gps()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(jdbc.queryForObject("SELECT status FROM works WHERE id = ?", String.class, failedId)).isEqualTo("SCHEDULED");
    assertThat(jdbc.queryForObject("SELECT checked_in_at FROM works WHERE id = ?", LocalDateTime.class, failedId)).isNull();
  }

  @Test
  void legacyCommandFingerprintRemainsValidAndLocationChangesConflict() throws Exception {
    var request = request(false, false);
    String key = UUID.randomUUID().toString();
    String workId = works.create(key, request).workId();
    String legacy = "CREATE:ScheduledWorkRequest[matchingId=" + request.matchingId()
        + ", jobPostId=" + request.jobPostId() + ", ownerMemberId=" + request.ownerMemberId()
        + ", workerMemberId=" + request.workerMemberId() + ", paymentId=" + request.paymentId()
        + ", workDate=2026-10-04, startTime=09:00, endTime=18:00]";
    String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(legacy.getBytes(StandardCharsets.UTF_8)));
    assertThat(jdbc.queryForObject("SELECT fingerprint FROM work_commands WHERE command_key = ?", String.class, key))
        .isEqualTo(expected);
    assertThat(works.create(key, request).workId()).isEqualTo(workId);
    var changed = new ScheduledWorkRequest(request.matchingId(), request.jobPostId(), request.ownerMemberId(),
        request.workerMemberId(), request.paymentId(), request.workDate(), request.startTime(), request.endTime(), null,
        new BigDecimal("37.5"), new BigDecimal("127"));
    assertThatThrownBy(() -> works.create(key, changed)).isInstanceOf(BusinessException.class);
    var fresh = request(true, false);
    String freshKey = UUID.randomUUID().toString();
    String freshId = works.create(freshKey, fresh).workId();
    var scaled = new ScheduledWorkRequest(fresh.matchingId(), fresh.jobPostId(), fresh.ownerMemberId(),
        fresh.workerMemberId(), fresh.paymentId(), fresh.workDate(), fresh.startTime(), fresh.endTime(), null,
        new BigDecimal("37.5000000"), new BigDecimal("127.0000000"));
    assertThat(works.create(freshKey, scaled).workId()).isEqualTo(freshId);
    var partial = new ScheduledWorkRequest(fresh.matchingId() + 1000, fresh.jobPostId(), fresh.ownerMemberId(),
        fresh.workerMemberId(), fresh.paymentId(), fresh.workDate(), fresh.startTime(), fresh.endTime(), null, new BigDecimal("37.5"), null);
    mvc.perform(post("/api/works/internal/scheduled").header("X-Internal-Secret", "work-test-internal-secret")
        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
        .content(mapper.writeValueAsString(partial))).andExpect(status().isBadRequest());
  }
}
