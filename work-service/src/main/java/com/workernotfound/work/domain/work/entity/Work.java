package com.workernotfound.work.domain.work.entity;

import com.workernotfound.work.domain.work.entity.enums.WorkStatus;
import com.workernotfound.work.domain.work.exception.WorkErrorCode;
import com.workernotfound.work.global.exception.BusinessException;
import jakarta.persistence.*;
import java.time.*;
import java.math.BigDecimal;
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

  @Column(nullable = false)
  private boolean endTimeNextDay;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private WorkStatus status;

  @Column(nullable = false)
  private LocalDateTime createdAt;

  private LocalDateTime canceledAt;
  private LocalDateTime confirmedAt;

  @Column(nullable = false)
  private long confirmationRevision;

  @Column(precision = 10, scale = 7) private BigDecimal latitude;
  @Column(precision = 10, scale = 7) private BigDecimal longitude;
  private LocalDateTime checkedInAt;
  private LocalDateTime startedAt;
  private LocalDateTime completedAt;
  private Double checkInDistanceMeters;
  @Column(length = 64) private String attendancePolicy;

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
      LocalTime endTime,
      boolean endTimeNextDay, BigDecimal latitude, BigDecimal longitude) {
    this.matchingId = matchingId;
    this.jobPostId = jobPostId;
    this.ownerMemberId = ownerMemberId;
    this.workerMemberId = workerMemberId;
    this.paymentId = paymentId;
    this.workDate = workDate;
    this.startTime = startTime;
    this.endTime = endTime;
    this.endTimeNextDay = endTimeNextDay;
    this.latitude = latitude;
    this.longitude = longitude;
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

  public void checkIn(LocalDateTime now, double distanceMeters, String policy) {
    requireStatus(WorkStatus.SCHEDULED);
    status = WorkStatus.CHECKED_IN;
    checkedInAt = now;
    checkInDistanceMeters = distanceMeters;
    attendancePolicy = policy;
  }

  public void start(LocalDateTime now) {
    requireStatus(WorkStatus.CHECKED_IN);
    status = WorkStatus.IN_PROGRESS;
    startedAt = now;
  }

  public void complete(LocalDateTime now) {
    requireStatus(WorkStatus.IN_PROGRESS);
    status = WorkStatus.COMPLETED;
    completedAt = now;
  }

  private void requireStatus(WorkStatus expected) {
    if (status != expected) throw new BusinessException(WorkErrorCode.WORK_STATE_CONFLICT);
  }

  public LocalDateTime scheduledStart() { return workDate.atTime(startTime); }

  public LocalDateTime scheduledEnd() {
    return (endTimeNextDay ? workDate.plusDays(1) : workDate).atTime(endTime);
  }

}
