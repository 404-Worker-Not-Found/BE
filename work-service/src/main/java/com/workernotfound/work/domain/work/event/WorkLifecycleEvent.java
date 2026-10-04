package com.workernotfound.work.domain.work.event;

import com.workernotfound.work.domain.work.entity.Work;
import java.time.*;
import java.util.UUID;

public record WorkLifecycleEvent(String eventId, String eventType, Long aggregateId, long revision,
    int version, LocalDateTime occurredAt, Long workId, Long matchingId, Long jobPostId,
    Long ownerMemberId, Long workerMemberId, Long actorMemberId, String status,
    LocalDate workDate, LocalTime startTime, LocalTime endTime, boolean endTimeNextDay) {
  public static WorkLifecycleEvent from(Work work, Long actor, LocalDateTime at) {
    long revision = switch (work.getStatus()) {
      case CHECKED_IN -> 1;
      case IN_PROGRESS -> 2;
      case COMPLETED -> 3;
      default -> throw new IllegalStateException("발행할 수 없는 근무 상태입니다.");
    };
    String type = switch (work.getStatus()) {
      case CHECKED_IN -> "WorkCheckedIn";
      case IN_PROGRESS -> "WorkStarted";
      case COMPLETED -> "WorkCompleted";
      default -> throw new IllegalStateException("발행할 수 없는 근무 상태입니다.");
    };
    return new WorkLifecycleEvent(UUID.randomUUID().toString(), type, work.getId(), revision, 1, at,
        work.getId(), work.getMatchingId(), work.getJobPostId(), work.getOwnerMemberId(),
        work.getWorkerMemberId(), actor, work.getStatus().name(), work.getWorkDate(),
        work.getStartTime(), work.getEndTime(), work.isEndTimeNextDay());
  }
}
