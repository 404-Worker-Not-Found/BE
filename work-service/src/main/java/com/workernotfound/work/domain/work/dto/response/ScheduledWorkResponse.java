package com.workernotfound.work.domain.work.dto.response;

public record ScheduledWorkResponse(String workId) {
  public static ScheduledWorkResponse from(Long workId) {
    return new ScheduledWorkResponse(workId.toString());
  }
}
