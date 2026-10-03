package com.workernotfound.work;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// endTimeNextDay가 없던 이전 형식의 근무 생성 요청이 HTTP 경계에서 계속 같은 멱등 명령으로 처리되는지 검증한다.
@AutoConfigureMockMvc
class WorkCreationCompatibilityTests extends com.workernotfound.work.support.IntegrationTestSupport {
  static final AtomicLong IDS = new AtomicLong(500_000);
  static final String SECRET = "work-test-internal-secret";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @Test
  void legacyDaytimeRequestIsStoredAsSameDay() throws Exception {
    long id = workId(create(key(), legacyBody(IDS.incrementAndGet(), "09:00:00", "18:00:00")));

    assertThat(storedNextDay(id)).isFalse();
  }

  @Test
  void legacyOvernightRequestIsStoredAsNextDay() throws Exception {
    long id = workId(create(key(), legacyBody(IDS.incrementAndGet(), "23:00:00", "02:00:00")));

    assertThat(storedNextDay(id)).isTrue();
  }

  @Test
  void explicitNullIsNormalizedWithTheSameRule() throws Exception {
    long day = workId(create(key(), body(IDS.incrementAndGet(), "09:00:00", "18:00:00", "null")));
    long night = workId(create(key(), body(IDS.incrementAndGet(), "22:00:00", "22:00:00", "null")));

    assertThat(storedNextDay(day)).isFalse();
    assertThat(storedNextDay(night)).isTrue();
  }

  @Test
  void legacyRetryReturnsWorkCommittedByPreviousVersion() throws Exception {
    long matchingId = IDS.incrementAndGet();
    String key = key();
    // 이전 버전이 근무를 만들고 응답만 유실된 상태를 재현한다. 지문은 필드 추가 전 record 문자열의 SHA-256이다.
    long workId = insertWorkCommittedByPreviousVersion(matchingId, key);

    create(key, legacyBody(matchingId, "23:00:00", "02:00:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.workId").value(String.valueOf(workId)));
    create(key, body(matchingId, "23:00:00", "02:00:00", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.workId").value(String.valueOf(workId)));

    assertSingleActiveWork(matchingId, workId);
  }

  @Test
  void legacyAndEquivalentExplicitRequestsShareOneCommandResult() throws Exception {
    long matchingId = IDS.incrementAndGet();
    String key = key();
    long workId = workId(create(key, legacyBody(matchingId, "23:00:00", "02:00:00")));

    for (String explicit : new String[] {"true", "null"}) {
      create(key, body(matchingId, "23:00:00", "02:00:00", explicit))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.workId").value(String.valueOf(workId)));
    }
    create(key, legacyBody(matchingId, "23:00:00", "02:00:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.workId").value(String.valueOf(workId)));

    assertSingleActiveWork(matchingId, workId);
  }

  @Test
  void explicitlyInconsistentNextDayFlagIsRejected() throws Exception {
    create(key(), body(IDS.incrementAndGet(), "22:00:00", "02:00:00", "false"))
        .andExpect(status().isBadRequest());
    create(key(), body(IDS.incrementAndGet(), "09:00:00", "18:00:00", "true"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void missingStartOrEndTimeIsRejectedAsBadRequest() throws Exception {
    String withoutStart =
        """
        {"matchingId":%d,"jobPostId":10,"ownerMemberId":20,"workerMemberId":30,
         "paymentId":"pay-legacy","workDate":"2026-09-20","endTime":"02:00:00"}
        """
            .formatted(IDS.incrementAndGet());
    String withoutEnd =
        """
        {"matchingId":%d,"jobPostId":10,"ownerMemberId":20,"workerMemberId":30,
         "paymentId":"pay-legacy","workDate":"2026-09-20","startTime":"23:00:00","endTimeNextDay":true}
        """
            .formatted(IDS.incrementAndGet());

    create(key(), withoutStart).andExpect(status().isBadRequest());
    create(key(), withoutEnd).andExpect(status().isBadRequest());
  }

  @Test
  void sameKeyWithDifferentScheduleOrIdentifierConflicts() throws Exception {
    long matchingId = IDS.incrementAndGet();
    String key = key();
    long workId = workId(create(key, legacyBody(matchingId, "23:00:00", "02:00:00")));

    create(key, legacyBody(matchingId, "23:00:00", "03:00:00")).andExpect(status().isConflict());
    create(key, body(matchingId, "09:00:00", "18:00:00", "false")).andExpect(status().isConflict());
    create(key, legacyBody(IDS.incrementAndGet(), "23:00:00", "02:00:00"))
        .andExpect(status().isConflict());

    assertSingleActiveWork(matchingId, workId);
  }

  @Test
  void resentCreationForCanceledWorkReturnsOriginalIdWithoutRestoringIt() throws Exception {
    long matchingId = IDS.incrementAndGet();
    String key = key();
    long workId = workId(create(key, legacyBody(matchingId, "23:00:00", "02:00:00")));
    mvc.perform(
            post("/api/works/internal/{workId}/cancel", workId)
                .header("X-Internal-Secret", SECRET)
                .header("Idempotency-Key", key()))
        .andExpect(status().isOk());

    create(key, legacyBody(matchingId, "23:00:00", "02:00:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.workId").value(String.valueOf(workId)));

    assertThat(jdbc.queryForObject("SELECT status FROM works WHERE id = ?", String.class, workId))
        .isEqualTo("CANCELED");
    assertThat(count("SELECT COUNT(*) FROM works WHERE matching_id = ?", matchingId)).isOne();
    assertThat(activeSlot(matchingId)).isNull();
  }

  private long insertWorkCommittedByPreviousVersion(long matchingId, String key) throws Exception {
    jdbc.update(
        "INSERT INTO works (matching_id, job_post_id, owner_member_id, worker_member_id, payment_id,"
            + " work_date, start_time, end_time, end_time_next_day, status, created_at, version)"
            + " VALUES (?, 10, 20, 30, 'pay-legacy', '2026-09-20', '23:00:00', '02:00:00', 1,"
            + " 'SCHEDULED', ?, 0)",
        matchingId,
        LocalDateTime.now());
    long workId =
        jdbc.queryForObject("SELECT id FROM works WHERE matching_id = ?", Long.class, matchingId);
    String legacyPayload =
        "CREATE:ScheduledWorkRequest[matchingId=%d, jobPostId=10, ownerMemberId=20, workerMemberId=30,"
                .formatted(matchingId)
            + " paymentId=pay-legacy, workDate=2026-09-20, startTime=23:00, endTime=02:00]";
    jdbc.update(
        "INSERT INTO work_commands (command_key, fingerprint, work_id) VALUES (?, ?, ?)",
        key,
        sha256(legacyPayload),
        workId);
    jdbc.update(
        "INSERT INTO work_matching_slots (matching_id, active_work_id) VALUES (?, ?)",
        matchingId,
        workId);
    jdbc.update(
        "INSERT INTO work_status_histories (work_id, previous_status, next_status, command_key,"
            + " occurred_at) VALUES (?, NULL, 'SCHEDULED', ?, ?)",
        workId,
        key,
        LocalDateTime.now());
    return workId;
  }

  private void assertSingleActiveWork(long matchingId, long workId) {
    assertThat(count("SELECT COUNT(*) FROM works WHERE matching_id = ?", matchingId)).isOne();
    assertThat(count("SELECT COUNT(*) FROM work_status_histories WHERE work_id = ?", workId))
        .isOne();
    assertThat(activeSlot(matchingId)).isEqualTo(workId);
  }

  private Long activeSlot(long matchingId) {
    return jdbc.queryForObject(
        "SELECT active_work_id FROM work_matching_slots WHERE matching_id = ?",
        Long.class,
        matchingId);
  }

  private long count(String sql, long id) {
    return jdbc.queryForObject(sql, Long.class, id);
  }

  private boolean storedNextDay(long workId) {
    return jdbc.queryForObject(
        "SELECT end_time_next_day FROM works WHERE id = ?", Boolean.class, workId);
  }

  private ResultActions create(String key, String body) throws Exception {
    return mvc.perform(
        post("/api/works/internal/scheduled")
            .header("X-Internal-Secret", SECRET)
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .content(body));
  }

  private long workId(ResultActions result) throws Exception {
    String response =
        result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return Long.parseLong(
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(response)
            .path("data")
            .path("workId")
            .asText());
  }

  private String legacyBody(long matchingId, String startTime, String endTime) {
    return """
        {"matchingId":%d,"jobPostId":10,"ownerMemberId":20,"workerMemberId":30,
         "paymentId":"pay-legacy","workDate":"2026-09-20","startTime":"%s","endTime":"%s"}
        """
        .formatted(matchingId, startTime, endTime);
  }

  private String body(long matchingId, String startTime, String endTime, String endTimeNextDay) {
    return """
        {"matchingId":%d,"jobPostId":10,"ownerMemberId":20,"workerMemberId":30,
         "paymentId":"pay-legacy","workDate":"2026-09-20","startTime":"%s","endTime":"%s",
         "endTimeNextDay":%s}
        """
        .formatted(matchingId, startTime, endTime, endTimeNextDay);
  }

  private String key() {
    return UUID.randomUUID().toString();
  }

  private String sha256(String payload) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
  }
}
