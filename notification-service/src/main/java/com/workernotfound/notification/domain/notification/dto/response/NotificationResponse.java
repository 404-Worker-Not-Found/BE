package com.workernotfound.notification.domain.notification.dto.response;

import com.workernotfound.notification.domain.notification.entity.Notification;
import java.time.LocalDateTime;

public record NotificationResponse(Long notificationId, String type, Long jobPostId,
    Long matchingId, Long applicationId, Long workId, LocalDateTime occurredAt, LocalDateTime createdAt,
    LocalDateTime readAt) {
  public static NotificationResponse from(Notification notification) {
    return new NotificationResponse(notification.getId(), notification.getNotificationType(),
        notification.getJobPostId(), notification.getMatchingId(), notification.getApplicationId(), notification.getWorkId(),
        notification.getOccurredAt(), notification.getCreatedAt(), notification.getReadAt());
  }
}
