package com.workernotfound.work.domain.work.dto.response;

import com.workernotfound.work.domain.work.entity.Work;
import com.workernotfound.work.domain.work.entity.enums.WorkStatus;
import java.time.*;

public record WorkResponse(
    Long workId,
    Long matchingId,
    Long jobPostId,
    Long ownerMemberId,
    Long workerMemberId,
    LocalDate workDate,
    LocalTime startTime,
    LocalTime endTime,
    WorkStatus status,
    LocalDateTime confirmedAt) {
  public static WorkResponse from(Work work) {
    return new WorkResponse(
        work.getId(),
        work.getMatchingId(),
        work.getJobPostId(),
        work.getOwnerMemberId(),
        work.getWorkerMemberId(),
        work.getWorkDate(),
        work.getStartTime(),
        work.getEndTime(),
        work.getStatus(),
        work.getConfirmedAt());
  }
}
