package com.workernotfound.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.work.domain.outbox.*;
import com.workernotfound.work.domain.work.dto.request.*;
import com.workernotfound.work.domain.work.event.*;
import com.workernotfound.work.domain.work.service.*;
import com.workernotfound.work.external.redis.WorkEventPublisher;
import com.workernotfound.work.global.security.AuthenticatedMember;
import com.workernotfound.work.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WorkOutboxIntegrationTests extends IntegrationTestSupport {
  static final AtomicLong IDS = new AtomicLong(900000);
  static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
  @Autowired WorkApplicationService works;
  @Autowired WorkConfirmationService confirmations;
  @Autowired WorkAttendanceService attendance;
  @Autowired WorkOutboxRelay relay;
  @Autowired WorkOutboxProperties properties;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired ObjectMapper mapper;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @MockitoSpyBean WorkOutboxRepository outbox;
  @MockitoSpyBean WorkEventPublisher publisher;
  @MockitoBean Clock clock;
  AtomicReference<Instant> time = new AtomicReference<>();
  ScheduledWorkRequest request;
  long workId;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM work_outbox");
    redis.delete(properties.stream());
    at("2026-10-05T09:00:00");
    when(clock.getZone()).thenReturn(ZONE);
    when(clock.instant()).thenAnswer(call -> time.get());
    long id = IDS.addAndGet(10);
    request = new ScheduledWorkRequest(id, id + 1, id + 2, id + 3, "payment-" + id,
        LocalDate.of(2026, 10, 5), LocalTime.of(9, 0), LocalTime.of(18, 0), false,
        new BigDecimal("37.5"), new BigDecimal("127"));
    String result = works.create(UUID.randomUUID().toString(), request).workId();
    workId = Long.parseLong(result);
    confirmations.confirm(new MatchConfirmedEvent(UUID.randomUUID().toString(), "MatchConfirmed", now(),
        id, 1L, 1, id, result, request.jobPostId(), request.ownerMemberId(), request.workerMemberId(),
        request.paymentId(), request.workDate(), request.startTime(), request.endTime()));
  }

  void at(String value) { time.set(LocalDateTime.parse(value).atZone(ZONE).toInstant()); }
  LocalDateTime now() { return LocalDateTime.ofInstant(time.get(), ZONE); }
  AuthenticatedMember worker() { return new AuthenticatedMember(1L, request.workerMemberId(), "WORKER"); }
  AuthenticatedMember owner() { return new AuthenticatedMember(2L, request.ownerMemberId(), "OWNER"); }
  void checkIn() { attendance.checkIn(worker(), workId, new CheckInRequest(request.latitude(), request.longitude())); }
  long eventId() { return jdbc.queryForObject("SELECT id FROM work_outbox WHERE work_id = ? AND revision = 1", Long.class, workId); }

  @Test
  void lifecyclePersistsThreeStableEventsAndReplaysDoNotAddMore() throws Exception {
    checkIn(); checkIn();
    attendance.start(owner(), workId); attendance.start(owner(), workId);
    at("2026-10-05T18:00:00");
    attendance.complete(owner(), workId); attendance.complete(owner(), workId); checkIn();
    var rows = jdbc.queryForList("SELECT * FROM work_outbox ORDER BY revision");
    assertThat(rows).extracting(row -> row.get("event_type"))
        .containsExactly("WorkCheckedIn", "WorkStarted", "WorkCompleted");
    assertThat(rows).extracting(row -> ((Number)row.get("revision")).longValue()).containsExactly(1L, 2L, 3L);
    for (var row : rows) {
      var payload = mapper.readTree(row.get("payload").toString());
      assertThat(payload.get("eventId").asText()).isEqualTo(row.get("event_id"));
      assertThat(payload.get("aggregateId").asLong()).isEqualTo(workId);
      assertThat(payload.get("workId").asLong()).isEqualTo(workId);
      assertThat(payload.get("version").asInt()).isEqualTo(1);
      assertThat(payload.has("paymentId")).isFalse();
      assertThat(payload.has("latitude")).isFalse();
    }
    relay.relay();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_outbox WHERE published_at IS NOT NULL", Long.class)).isEqualTo(3);
    assertThat(redis.opsForStream().size(properties.stream())).isEqualTo(3);
  }

  @Test
  void outboxFailureRollsBackStatusAndActorHistory() {
    new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status ->
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("injected persistence failure")).when(outbox).append(any(), anyString()));
    assertThatThrownBy(this::checkIn).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    assertThat(jdbc.queryForObject("SELECT status FROM works WHERE id = ?", String.class, workId)).isEqualTo("SCHEDULED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_status_histories WHERE work_id = ?", Long.class, workId)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_outbox", Long.class)).isZero();
  }

  @Test
  void concurrentCheckInRecordsOneEvent() throws Exception {
    var pool = Executors.newFixedThreadPool(6);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < 6; i++) futures.add(pool.submit(this::checkIn));
      for (var future : futures) future.get(15, TimeUnit.SECONDS);
    } finally { pool.shutdownNow(); }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_outbox", Long.class)).isEqualTo(1);
  }

  @Test
  void publishFailureRetainsPayloadAndWaitsBeforeRetrying() {
    checkIn();
    String payload = jdbc.queryForObject("SELECT payload FROM work_outbox WHERE id = ?", String.class, eventId());
    doThrow(new IllegalStateException("injected Redis failure")).when(publisher).publish(any());
    relay.relay(); relay.relay();
    verify(publisher, times(1)).publish(any());
    assertThat(jdbc.queryForObject("SELECT retry_count FROM work_outbox WHERE id = ?", Integer.class, eventId())).isEqualTo(1);
    doCallRealMethod().when(publisher).publish(any());
    at("2026-10-05T09:00:01");
    relay.relay();
    assertThat(redis.opsForStream().size(properties.stream())).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT payload FROM work_outbox WHERE id = ?", String.class, eventId())).isEqualTo(payload);
  }

  @Test
  void acknowledgementLossRedeliversSameEventIdAndPayload() {
    checkIn();
    doThrow(new IllegalStateException("injected acknowledgement loss"))
        .doCallRealMethod().when(outbox).published(anyLong(), anyString(), any());
    relay.relay();
    at("2026-10-05T09:00:01");
    relay.relay();
    var records = redis.opsForStream().range(properties.stream(), Range.unbounded());
    assertThat(records).hasSize(2);
    assertThat(records.get(0).getId()).isNotEqualTo(records.get(1).getId());
    assertThat(records.get(0).getValue()).isEqualTo(records.get(1).getValue());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_outbox WHERE published_at IS NOT NULL", Long.class)).isEqualTo(1);
  }

  @Test
  void oneClaimWinsAndExpiredLeaseCanBeReclaimedWithoutStaleWriterOverwritingIt() throws Exception {
    checkIn();
    long id = eventId();
    var pool = Executors.newFixedThreadPool(6);
    List<String> winners = new CopyOnWriteArrayList<>();
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < 6; i++) futures.add(pool.submit(() -> {
        String token = UUID.randomUUID().toString();
        if (outbox.claim(id, token, now(), now().plusSeconds(30)).isPresent()) winners.add(token);
      }));
      for (var future : futures) future.get(15, TimeUnit.SECONDS);
    } finally { pool.shutdownNow(); }
    assertThat(winners).hasSize(1);
    at("2026-10-05T09:00:30");
    String replacement = UUID.randomUUID().toString();
    assertThat(outbox.claim(id, replacement, now(), now().plusSeconds(30))).isPresent();
    outbox.published(id, winners.get(0), now());
    outbox.failed(id, winners.get(0), now().plusHours(1));
    assertThat(jdbc.queryForObject("SELECT lease_token FROM work_outbox WHERE id = ?", String.class, id)).isEqualTo(replacement);
    assertThat(jdbc.queryForObject("SELECT published_at FROM work_outbox WHERE id = ?", LocalDateTime.class, id)).isNull();
    assertThat(jdbc.queryForObject("SELECT retry_count FROM work_outbox WHERE id = ?", Integer.class, id)).isZero();
    outbox.published(id, replacement, now());
    assertThat(outbox.candidates(now().plusMinutes(1), 100)).doesNotContain(id);
  }
}
