package com.workernotfound.work.domain.work.event;

import com.workernotfound.work.domain.work.entity.*;
import java.time.*;
import java.util.UUID;

public record WorkCancellationEvent(String eventId, String eventType, Long aggregateId, long revision,
    int version, LocalDateTime occurredAt, Long workId, Long matchingId, Long jobPostId,
    Long ownerMemberId, Long workerMemberId, Long actorMemberId, String actorRole, String status,
    LocalDate workDate, LocalTime startTime, LocalTime endTime, boolean endTimeNextDay) {
  public static WorkCancellationEvent from(Work work, WorkCancellationRequest request, Long actor,
      String role, LocalDateTime at) {
    String type = switch (request.getStatus()) {
      case PENDING -> "WorkCancellationRequested";
      case REJECTED -> "WorkCancellationRejected";
      case WITHDRAWN -> "WorkCancellationWithdrawn";
      case EXPIRED -> "WorkCancellationExpired";
      default -> throw new IllegalStateException("승인은 WorkCanceled 이벤트로 기록합니다.");
    };
    long revision = request.getDecidedAt() == null ? 1 : 2;
    return new WorkCancellationEvent(UUID.randomUUID().toString(), type, request.getId(), revision, 1, at,
        work.getId(), work.getMatchingId(), work.getJobPostId(), work.getOwnerMemberId(), work.getWorkerMemberId(),
        actor, role, request.getStatus().name(), work.getWorkDate(), work.getStartTime(), work.getEndTime(), work.isEndTimeNextDay());
  }
}
