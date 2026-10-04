package com.workernotfound.work.domain.work.dto.response;

import com.workernotfound.work.domain.work.entity.Work;
import com.workernotfound.work.domain.work.entity.enums.WorkStatus;
import java.time.*;
import java.math.BigDecimal;

public record WorkResponse(
    Long workId,
    Long matchingId,
    Long jobPostId,
    Long ownerMemberId,
    Long workerMemberId,
    LocalDate workDate,
    LocalTime startTime,
    LocalTime endTime,
    boolean endTimeNextDay,
    WorkStatus status,
    LocalDateTime confirmedAt, BigDecimal latitude, BigDecimal longitude,
    LocalDateTime checkedInAt, LocalDateTime startedAt, LocalDateTime completedAt) {
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
        work.isEndTimeNextDay(),
        work.getStatus(),
        work.getConfirmedAt(), work.getLatitude(), work.getLongitude(),
        work.getCheckedInAt(), work.getStartedAt(), work.getCompletedAt());
  }
}
