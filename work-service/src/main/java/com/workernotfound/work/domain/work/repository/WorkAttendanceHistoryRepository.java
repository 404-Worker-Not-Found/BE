package com.workernotfound.work.domain.work.repository;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class WorkAttendanceHistoryRepository {
  private final JdbcTemplate jdbc;

  public void record(Long workId, String previous, String next, Long actorMemberId, LocalDateTime at) {
    jdbc.update("INSERT INTO work_status_histories (work_id, previous_status, next_status, actor_member_id, occurred_at) "
        + "VALUES (?, ?, ?, ?, ?)", workId, previous, next, actorMemberId, at);
  }
}
