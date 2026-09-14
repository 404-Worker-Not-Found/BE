package com.workernotfound.work;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.workernotfound.work.domain.work.dto.request.ScheduledWorkRequest;
import com.workernotfound.work.domain.work.service.WorkApplicationService;
import com.workernotfound.work.global.exception.BusinessException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class WorkIntegrationTests extends com.workernotfound.work.support.IntegrationTestSupport {
  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  com.workernotfound.work.domain.work.repository.WorkCommandRepository commandRepository;

  static final AtomicLong IDS = new AtomicLong(100);

  @Autowired WorkApplicationService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;

  ScheduledWorkRequest request(long matchingId) {
    return new ScheduledWorkRequest(
        matchingId,
        10L,
        20L,
        30L,
        "payment",
        LocalDate.of(2026, 9, 20),
        LocalTime.of(23, 0),
        LocalTime.of(2, 0));
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  long count(String table, long workId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE work_id = ?", Long.class, workId);
  }

  @Test
  void failedHistoryWriteRollsBackWorkAndCommand() {
    var request = request(IDS.incrementAndGet());
    String command = key();
    org.mockito.Mockito.doThrow(
            new org.springframework.dao.DataIntegrityViolationException("test history failure"))
        .when(commandRepository)
        .history(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("SCHEDULED"),
            org.mockito.ArgumentMatchers.eq(command));
    try {
      assertThatThrownBy(() -> service.create(command, request))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    } finally {
      org.mockito.Mockito.reset(commandRepository);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM works WHERE matching_id = ?",
                Long.class,
                request.matchingId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_commands WHERE command_key = ?", Long.class, command))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_matching_slots WHERE matching_id = ?",
                Long.class,
                request.matchingId()))
        .isZero();
    assertThat(service.create(command, request).workId()).isNotBlank();
  }

  @Test
  void retryCompensateAndNewAttempt() {
    var request = request(IDS.incrementAndGet());
    String create = key();
    var first = service.create(create, request);
    assertThat(service.create(create, request)).isEqualTo(first);
    String cancel = key();
    long oldId = Long.parseLong(first.workId());
    service.cancel(cancel, oldId);
    service.cancel(cancel, oldId);
    assertThat(service.create(create, request)).isEqualTo(first);
    var next = service.create(key(), request);
    assertThat(next.workId()).isNotEqualTo(first.workId());
    service.cancel(key(), oldId);
    assertThat(
            jdbc.queryForObject(
                "SELECT active_work_id FROM work_matching_slots WHERE matching_id = ?",
                Long.class,
                request.matchingId()))
        .isEqualTo(Long.parseLong(next.workId()));
    assertThat(count("work_status_histories", oldId)).isEqualTo(2);
  }

  @Test
  void mismatchedCommandAndActiveDuplicateAreRejectedWithoutWrites() {
    var r = request(IDS.incrementAndGet());
    String command = key();
    var work = service.create(command, r);
    assertThatThrownBy(() -> service.create(command, request(IDS.incrementAndGet())))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.cancel(command, Long.parseLong(work.workId())))
        .isInstanceOf(BusinessException.class);
    String duplicate = key();
    assertThatThrownBy(() -> service.create(duplicate, r)).isInstanceOf(BusinessException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_commands WHERE command_key = ?", Long.class, duplicate))
        .isZero();
  }

  @Test
  void nonScheduledCancellationAndMissingWorkAreRejected() {
    long id = Long.parseLong(service.create(key(), request(IDS.incrementAndGet())).workId());
    jdbc.update("UPDATE works SET status = 'IN_PROGRESS' WHERE id = ?", id);
    assertThatThrownBy(() -> service.cancel(key(), id)).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.cancel(key(), Long.MAX_VALUE))
        .isInstanceOf(BusinessException.class);
    assertThat(count("work_status_histories", id)).isEqualTo(1);
  }

  @Test
  void concurrentIdenticalCreationReturnsOneWork() throws Exception {
    var r = request(IDS.incrementAndGet());
    String command = key();
    var results = concurrent(() -> service.create(command, r).workId());
    assertThat(new HashSet<>(results)).hasSize(1);
    assertThat(count("work_status_histories", Long.parseLong(results.get(0)))).isEqualTo(1);
  }

  @Test
  void concurrentDistinctCreationHasOneWinner() throws Exception {
    var r = request(IDS.incrementAndGet());
    var results =
        concurrent(
            () -> {
              try {
                return service.create(key(), r).workId();
              } catch (BusinessException e) {
                return "conflict";
              }
            });
    assertThat(results.stream().filter(v -> !v.equals("conflict")).count()).isEqualTo(1);
  }

  @Test
  void concurrentCancellationRecordsOneTransition() throws Exception {
    long id = Long.parseLong(service.create(key(), request(IDS.incrementAndGet())).workId());
    concurrent(
        () -> {
          service.cancel(key(), id);
          return "ok";
        });
    assertThat(count("work_status_histories", id)).isEqualTo(2);
  }

  List<String> concurrent(Callable<String> operation) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(6);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<String>> futures = new ArrayList<>();
      for (int i = 0; i < 6; i++)
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  return operation.call();
                }));
      start.countDown();
      List<String> values = new ArrayList<>();
      for (var future : futures) values.add(future.get(30, TimeUnit.SECONDS));
      return values;
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void authenticationValidationAndSwagger() throws Exception {
    mvc.perform(post("/api/works/internal/scheduled"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("GLOBAL-401-001"));
    mvc.perform(post("/ctx/api/works/internal/scheduled").contextPath("/ctx"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/works/internal/scheduled").header("X-Internal-Secret", "wrong"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/works/internal/scheduled")
                .header("X-Internal-Secret", "work-test-internal-secret")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/works/internal/scheduled")
                .header("X-Internal-Secret", "work-test-internal-secret")
                .header("Idempotency-Key", key())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/works/internal/scheduled']").exists());
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
  }

  @Test
  void httpContractAcceptsSagaPayloadAndReturnsStringIdentifier() throws Exception {
    String body =
        """
        {"matchingId":%d,"jobPostId":10,"ownerMemberId":20,"workerMemberId":30,
         "paymentId":"pay-1","workDate":"2026-09-20","startTime":"09:00:00","endTime":"18:00:00"}
        """
            .formatted(IDS.incrementAndGet());
    String response =
        mvc.perform(
                post("/api/works/internal/scheduled")
                    .header("X-Internal-Secret", "work-test-internal-secret")
                    .header("Idempotency-Key", key())
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.workId").isString())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(response)
            .path("data")
            .path("workId")
            .asText();
    mvc.perform(
            post("/api/works/internal/" + id + "/cancel")
                .header("X-Internal-Secret", "work-test-internal-secret")
                .header("Idempotency-Key", key()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
  }
}
