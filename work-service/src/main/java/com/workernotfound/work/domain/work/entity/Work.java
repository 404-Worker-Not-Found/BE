package com.workernotfound.work.domain.work.entity;

import com.workernotfound.work.domain.work.entity.enums.WorkStatus;
import com.workernotfound.work.domain.work.exception.WorkErrorCode;
import com.workernotfound.work.global.exception.BusinessException;
import jakarta.persistence.*;
import java.time.*;
import lombok.*;

@Entity
@Table(name = "works")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Work {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long matchingId;

  @Column(nullable = false)
  private Long jobPostId;

  @Column(nullable = false)
  private Long ownerMemberId;

  @Column(nullable = false)
  private Long workerMemberId;

  @Column(nullable = false)
  private String paymentId;

  @Column(nullable = false)
  private LocalDate workDate;

  @Column(nullable = false)
  private LocalTime startTime;

  @Column(nullable = false)
  private LocalTime endTime;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private WorkStatus status;

  @Column(nullable = false)
  private LocalDateTime createdAt;

  private LocalDateTime canceledAt;
  private LocalDateTime confirmedAt;

  @Column(nullable = false)
  private long confirmationRevision;

  @Version private Long version;

  @Builder
  private Work(
      Long matchingId,
      Long jobPostId,
      Long ownerMemberId,
      Long workerMemberId,
      String paymentId,
      LocalDate workDate,
      LocalTime startTime,
      LocalTime endTime) {
    this.matchingId = matchingId;
    this.jobPostId = jobPostId;
    this.ownerMemberId = ownerMemberId;
    this.workerMemberId = workerMemberId;
    this.paymentId = paymentId;
    this.workDate = workDate;
    this.startTime = startTime;
    this.endTime = endTime;
    this.status = WorkStatus.SCHEDULED;
    this.createdAt = LocalDateTime.now();
  }

  public void confirm(long revision, LocalDateTime occurredAt) {
    if (revision <= confirmationRevision || status == WorkStatus.CANCELED) return;
    if (confirmedAt == null) confirmedAt = occurredAt;
    confirmationRevision = revision;
  }

  public boolean cancel() {
    if (status == WorkStatus.CANCELED) return false;
    if (status != WorkStatus.SCHEDULED || confirmedAt != null)
      throw new BusinessException(WorkErrorCode.CANNOT_CANCEL);
    status = WorkStatus.CANCELED;
    canceledAt = LocalDateTime.now();
    return true;
  }
}
