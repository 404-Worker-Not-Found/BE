package com.workernotfound.work.domain.work.entity;

import com.workernotfound.work.domain.work.entity.enums.WorkCancellationStatus;
import com.workernotfound.work.domain.work.exception.WorkErrorCode;
import com.workernotfound.work.global.exception.BusinessException;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;

@Entity
@Table(name = "work_cancellation_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkCancellationRequest {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(nullable = false) private Long workId;
  @Column(nullable = false) private Long requesterMemberId;
  @Column(nullable = false, length = 20) private String requesterRole;
  @Column(nullable = false, length = 128) private String requestKey;
  @Column(nullable = false, length = 500) private String reason;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private WorkCancellationStatus status;
  @Column(nullable = false) private LocalDateTime createdAt;
  private LocalDateTime decidedAt;
  private Long decisionMemberId;
  @Version private Long version;

  @Builder
  private WorkCancellationRequest(Long workId, Long requesterMemberId, String requesterRole,
      String requestKey, String reason, LocalDateTime createdAt) {
    this.workId = workId;
    this.requesterMemberId = requesterMemberId;
    this.requesterRole = requesterRole;
    this.requestKey = requestKey;
    this.reason = reason;
    this.createdAt = createdAt;
    this.status = WorkCancellationStatus.PENDING;
  }

  public void resolve(WorkCancellationStatus next, Long actor, LocalDateTime now) {
    if (status != WorkCancellationStatus.PENDING || next == WorkCancellationStatus.PENDING)
      throw new BusinessException(WorkErrorCode.CANCELLATION_STATE_CONFLICT);
    status = next;
    decisionMemberId = actor;
    decidedAt = now;
  }
}
