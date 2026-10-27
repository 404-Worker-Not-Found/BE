package com.workernotfound.work;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.workernotfound.work.domain.outbox.*;
import com.workernotfound.work.domain.work.dto.request.*;
import com.workernotfound.work.domain.work.event.MatchConfirmedEvent;
import com.workernotfound.work.domain.work.service.*;
import com.workernotfound.work.global.security.AuthenticatedMember;
import com.workernotfound.work.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;

class WorkNoShowIntegrationTests extends IntegrationTestSupport {
  static final AtomicLong IDS = new AtomicLong(1_500_000);
  static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
  @Autowired WorkApplicationService works;
  @Autowired WorkConfirmationService confirmations;
  @Autowired WorkAttendanceService attendance;
  @Autowired WorkNoShowCommandService noShows;
  @Autowired WorkNoShowReconciler reconciler;
  @Autowired JdbcTemplate jdbc;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @MockitoBean Clock clock;
  @MockitoSpyBean WorkOutboxRepository outbox;
  AtomicReference<Instant> instant = new AtomicReference<>();
  ScheduledWorkRequest request;
  long id;

  @BeforeEach
  void setup() {
    at("2020-10-28T17:30:00");
    when(clock.instant()).thenAnswer(call -> instant.get());
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    long next = IDS.addAndGet(10);
    request = new ScheduledWorkRequest(next, next + 1, next + 2, next + 3, "payment-" + next,
        LocalDate.of(2020, 10, 28), LocalTime.of(17, 0), LocalTime.of(23, 0), false,
        new BigDecimal("37.5"), new BigDecimal("127"));
    id = Long.parseLong(works.create(UUID.randomUUID().toString(), request).workId());
    confirmations.confirm(new MatchConfirmedEvent(UUID.randomUUID().toString(), "MatchConfirmed",
        LocalDateTime.of(2020, 10, 28, 16, 0), next, 1L, 1, next, String.valueOf(id), request.jobPostId(),
        request.ownerMemberId(), request.workerMemberId(), request.paymentId(), request.workDate(), request.startTime(), request.endTime()));
  }

  void at(String value) { instant.set(LocalDateTime.parse(value).atZone(ZONE).toInstant()); }
  String status() { return jdbc.queryForObject("SELECT status FROM works WHERE id = ?", String.class, id); }
  long events() { return jdbc.queryForObject("SELECT COUNT(*) FROM work_outbox WHERE work_id = ?", Long.class, id); }

  @Test
  void lastCheckInInstantIsAllowedAndCheckedInWorkIsNotMarked() {
    assertThat(noShows.markNoShow(id)).isFalse();
    attendance.checkIn(new AuthenticatedMember(1L, request.workerMemberId(), "WORKER"), id,
        new CheckInRequest(request.latitude(), request.longitude()));
    at("2020-10-28T17:30:00.000001");
    assertThat(noShows.markNoShow(id)).isFalse();
    assertThat(status()).isEqualTo("CHECKED_IN");
  }

  @Test
  void schedulerRecordsOneSystemEventAfterDeadlineUsingScheduleZone() throws Exception {
    reconciler.reconcile();
    assertThat(status()).isEqualTo("SCHEDULED");
    at("2020-10-28T17:30:00.000001");
    reconciler.reconcile();
    reconciler.reconcile();
    assertThat(status()).isEqualTo("NO_SHOW");
    assertThat(events()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT no_show_deadline FROM works WHERE id = ?", LocalDateTime.class, id))
        .isEqualTo(LocalDateTime.of(2020, 10, 28, 17, 30));
    String payload = jdbc.queryForObject("SELECT payload FROM work_outbox WHERE work_id = ?", String.class, id);
    var event = mapper.readTree(payload);
    assertThat(event.path("actorRole").asText()).isEqualTo("SYSTEM");
    assertThat(event.path("actorMemberId").isNull()).isTrue();
    assertThat(event.path("eventType").asText()).isEqualTo("WorkNoShow");
    assertThat(event.path("revision").asLong()).isEqualTo(4);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_status_histories WHERE work_id = ? AND system_actor = TRUE",
        Long.class, id)).isEqualTo(1);
  }

  @Test
  void missingLocationAndUnconfirmedWorkAreNotBlamedForNoShow() {
    at("2020-10-28T18:00:00");
    jdbc.update("UPDATE works SET latitude = NULL, longitude = NULL WHERE id = ?", id);
    assertThat(noShows.markNoShow(id)).isFalse();
    jdbc.update("UPDATE works SET latitude = 37.5, longitude = 127, confirmed_at = NULL WHERE id = ?", id);
    assertThat(noShows.markNoShow(id)).isFalse();
    assertThat(status()).isEqualTo("SCHEDULED");
    assertThat(events()).isZero();
  }

  @Test
  void outboxFailureRollsBackNoShowAndSystemHistory() {
    at("2020-10-28T18:00:00");
    new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx ->
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("injected"))
            .when(outbox).append(any(), anyString()));
    assertThatThrownBy(() -> noShows.markNoShow(id)).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    assertThat(status()).isEqualTo("SCHEDULED");
    assertThat(jdbc.queryForObject("SELECT no_show_at FROM works WHERE id = ?", LocalDateTime.class, id)).isNull();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_status_histories WHERE work_id = ? AND system_actor = TRUE",
        Long.class, id)).isZero();
    assertThat(events()).isZero();
  }

  @Test
  void concurrentSchedulersOnlyMarkOnce() throws Exception {
    at("2020-10-28T18:00:00");
    var executor = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      var first = executor.submit(() -> { start.await(); return noShows.markNoShow(id); });
      var second = executor.submit(() -> { start.await(); return noShows.markNoShow(id); });
      start.countDown();
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
      assertThat(events()).isEqualTo(1);
    } finally { executor.shutdownNow(); }
  }
}
