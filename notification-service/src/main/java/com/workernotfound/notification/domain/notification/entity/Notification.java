package com.workernotfound.notification.domain.notification.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.*;

@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(nullable = false, length = 128) private String eventId;
  @Column(nullable = false) private Long memberId;
  @Column(nullable = false, length = 20) private String memberRole;
  @Column(nullable = false, length = 32) private String notificationType;
  @Column(nullable = false) private Long jobPostId;
  private Long matchingId;
  @Column(nullable = false) private Long applicationId;
  @Column(nullable = false) private LocalDateTime occurredAt;
  @Column(nullable = false) private LocalDateTime createdAt;
  private LocalDateTime readAt;

  @Builder
  private Notification(String eventId, Long memberId, String memberRole, String notificationType,
      Long jobPostId, Long matchingId, Long applicationId, LocalDateTime occurredAt) {
    this.eventId = eventId;
    this.memberId = memberId;
    this.memberRole = memberRole;
    this.notificationType = notificationType;
    this.jobPostId = jobPostId;
    this.matchingId = matchingId;
    this.applicationId = applicationId;
    this.occurredAt = occurredAt;
    this.createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
  }

  public void markRead() {
    if (readAt == null) readAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
  }
}
