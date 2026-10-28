package com.workernotfound.work.domain.work.dto.response;

import com.workernotfound.work.domain.work.entity.WorkCancellationRequest;
import com.workernotfound.work.domain.work.entity.enums.WorkCancellationStatus;
import java.time.LocalDateTime;

public record WorkCancellationResponse(Long requestId, Long workId, Long requesterMemberId, String requesterRole,
    String reason, WorkCancellationStatus status, LocalDateTime createdAt, LocalDateTime decidedAt, Long decisionMemberId) {
  public static WorkCancellationResponse from(WorkCancellationRequest request) {
    return new WorkCancellationResponse(request.getId(), request.getWorkId(), request.getRequesterMemberId(),
        request.getRequesterRole(), request.getReason(), request.getStatus(), request.getCreatedAt(),
        request.getDecidedAt(), request.getDecisionMemberId());
  }
}
