package com.workernotfound.matching;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.matching.entity.*;
import com.workernotfound.matching.domain.matching.entity.enums.*;
import com.workernotfound.matching.domain.matching.event.WorkTerminationEvent;
import com.workernotfound.matching.domain.matching.repository.*;
import com.workernotfound.matching.domain.matching.service.WorkTerminationService;
import com.workernotfound.matching.external.redis.WorkTerminationEventConsumer;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class WorkTerminationIntegrationTests extends IntegrationTestSupport {
  static final AtomicLong IDS = new AtomicLong(4_000_000);
  static final LocalDateTime AT = LocalDateTime.of(2026, 10, 28, 18, 0);
  @Autowired ApplicationRepository applications;
  @Autowired MatchingRepository matchings;
  @Autowired MatchingConfirmationSagaRepository sagas;
  @Autowired WorkTerminationService service;
  @Autowired WorkTerminationEventConsumer consumer;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired StringRedisTemplate redis;
  @Autowired ObjectMapper mapper;
  @MockitoSpyBean com.workernotfound.matching.domain.outbox.service.OutboxEventCommandService outbox;
  final List<Fixture> fixtures = new ArrayList<>();
  record Fixture(long application, long matching, long work, long job) {}

  Fixture fixture() {
    Fixture result = new TransactionTemplate(transactions).execute(tx -> {
      long job = IDS.incrementAndGet(), work = IDS.incrementAndGet();
      var application = Application.builder().jobPostId(job).ownerMemberId(20L).workerMemberId(30L)
          .jobApplicationAdmissionId(job).appliedAt(AT.minusHours(12)).build();
      application.select(); applications.saveAndFlush(application);
      var matching = Matching.builder().application(application).selectionType(MatchingSelectionType.MANUAL).selectedAt(AT.minusHours(11)).build();
      matching.confirm(AT.minusHours(10)); matchings.saveAndFlush(matching);
      String key = UUID.randomUUID().toString();
      var saga = MatchingConfirmationSaga.builder().matching(matching).seatReservationCommandId(key).seatConfirmationCommandId(key)
          .seatCompensationCommandId(key).paymentLockCommandId(key).paymentCompensationCommandId(key).workCreationCommandId(key)
          .workCompensationCommandId(key).chatCreationCommandId(key).chatCompensationCommandId(key).startedAt(AT.minusHours(11)).build();
      saga.recordWork(Long.toString(work)); saga.complete(AT.minusHours(10)); sagas.saveAndFlush(saga);
      return new Fixture(application.getId(), matching.getId(), work, job);
    });
    fixtures.add(result);
    return result;
  }

  @AfterEach
  void cleanup() {
    for (var fixture : fixtures) {
      jdbc.update("DELETE FROM matching_work_event_receipts WHERE matching_id=?", fixture.matching());
      jdbc.update("DELETE FROM matching_work_terminations WHERE matching_id=?", fixture.matching());
      jdbc.update("DELETE FROM matching_status_histories WHERE matching_id=?", fixture.matching());
      jdbc.update("DELETE FROM application_status_histories WHERE application_id=?", fixture.application());
      jdbc.update("DELETE FROM outbox_events WHERE aggregate_type='APPLICATION' AND aggregate_id=?", fixture.application());
      jdbc.update("DELETE FROM matching_confirmation_sagas WHERE matching_id=?", fixture.matching());
      jdbc.update("DELETE FROM matchings WHERE id=?", fixture.matching());
      jdbc.update("DELETE FROM applications WHERE id=?", fixture.application());
    }
  }
  WorkTerminationEvent event(Fixture fixture, String type) {
    boolean noShow = type.equals("WorkNoShow");
    long revision = noShow ? 4 : type.equals("WorkCompleted") ? 3 : 5;
    String status = noShow ? "NO_SHOW" : type.equals("WorkCompleted") ? "COMPLETED" : "CANCELED";
    return new WorkTerminationEvent(UUID.randomUUID().toString(), type, fixture.work(), revision, 1, AT,
        fixture.work(), fixture.matching(), fixture.job(), 20L, 30L, noShow ? null : 20L, noShow ? "SYSTEM" : "OWNER", status);
  }
  String matchingStatus(Fixture fixture) { return jdbc.queryForObject("SELECT status FROM matchings WHERE id=?", String.class, fixture.matching()); }
  String applicationStatus(Fixture fixture) { return jdbc.queryForObject("SELECT status FROM applications WHERE id=?", String.class, fixture.application()); }
  Map<String, String> fields(WorkTerminationEvent event) throws Exception {
    return Map.of("aggregateType", "WORK", "aggregateId", Long.toString(event.workId()), "eventId", event.eventId(), "eventType", event.eventType(),
        "revision", Long.toString(event.revision()), "version", "1", "occurredAt", event.occurredAt().toString(), "payload", mapper.writeValueAsString(event));
  }

  @Test
  void outboxFailureRollsBackMatchingApplicationAndReceipt() {
    var fixture = fixture();
    org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("injected"))
        .when(outbox).saveApplicationEvent(org.mockito.ArgumentMatchers.any());
    assertThatThrownBy(() -> service.receive(event(fixture, "WorkNoShow")))
        .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    assertThat(matchingStatus(fixture)).isEqualTo("CONFIRMED");
    assertThat(applicationStatus(fixture)).isEqualTo("SELECTED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM matching_work_event_receipts WHERE matching_id=?", Long.class, fixture.matching())).isZero();
  }

  @Test
  void completedWorkClosesMatchingButRetainsSelectionFact() {
    var fixture = fixture();
    var event = event(fixture, "WorkCompleted");
    assertThat(service.receive(event)).isEqualTo("APPLIED");
    assertThat(service.receive(event)).isEqualTo("APPLIED");
    assertThat(matchingStatus(fixture)).isEqualTo("COMPLETED");
    assertThat(applicationStatus(fixture)).isEqualTo("SELECTED");
    assertThat(service.receive(event(fixture, "WorkCompleted"))).isEqualTo("ALREADY_TERMINATED");
  }

  @Test
  void noShowClosesMatchingAndSelectedApplicationWithoutDuplicateHistory() {
    var fixture = fixture();
    var event = event(fixture, "WorkNoShow");
    assertThat(service.receive(event)).isEqualTo("APPLIED");
    assertThat(service.receive(event)).isEqualTo("APPLIED");
    assertThat(applicationStatus(fixture)).isEqualTo("CANCELED");
    assertThat(matchingStatus(fixture)).isEqualTo("CANCELED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM matching_status_histories WHERE matching_id=?", Long.class, fixture.matching())).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_type='APPLICATION' AND aggregate_id=?", Long.class, fixture.application())).isEqualTo(1);
  }

  @Test
  void oldSagaWorkCannotEndCurrentConfirmedMatching() {
    var fixture = fixture();
    var event = event(fixture, "WorkCanceled");
    var wrong = new WorkTerminationEvent(event.eventId(), event.eventType(), event.workId() + 100, 5, 1, AT,
        event.workId() + 100, event.matchingId(), event.jobPostId(), 20L, 30L, 20L, "OWNER", "CANCELED");
    assertThat(service.receive(wrong)).isEqualTo("REJECTED_WORK_MISMATCH");
    assertThat(matchingStatus(fixture)).isEqualTo("CONFIRMED");
    assertThatThrownBy(() -> service.receive(event)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void malformedEnvelopeStaysPendingAndValidFollowingEventStillWorks() throws Exception {
    var fixture = fixture();
    var event = event(fixture, "WorkCanceled");
    var bad = new HashMap<>(fields(event)); bad.put("version", "2");
    redis.opsForStream().add(StreamRecords.newRecord().in("work:domain-events").ofMap(bad));
    consumer.poll();
    assertThat(matchingStatus(fixture)).isEqualTo("CONFIRMED");
    redis.opsForStream().add(StreamRecords.newRecord().in("work:domain-events").ofMap(fields(event)));
    consumer.poll(); consumer.poll();
    assertThat(matchingStatus(fixture)).isEqualTo("CANCELED");
  }

  @Test
  void simultaneousDuplicateEventsOnlyApplyOneTransition() throws Exception {
    var fixture = fixture();
    var executor = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      var first = executor.submit(() -> { start.await(); return service.receive(event(fixture, "WorkCanceled")); });
      var second = executor.submit(() -> { start.await(); return service.receive(event(fixture, "WorkCanceled")); });
      start.countDown();
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("APPLIED", "ALREADY_TERMINATED");
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM matching_status_histories WHERE matching_id=?", Long.class, fixture.matching())).isEqualTo(1);
    } finally { executor.shutdownNow(); }
  }
}
