package com.workernotfound.notification.domain.push.service;

import com.workernotfound.notification.domain.notification.entity.Notification;
import com.workernotfound.notification.domain.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class NotificationWriter {
  private final NotificationRepository notifications;
  private final JdbcTemplate jdbc;

  @Transactional(propagation = Propagation.MANDATORY)
  public void save(Notification notification) {
    notifications.saveAndFlush(notification);
    jdbc.update("""
        INSERT INTO notification_push_deliveries
          (notification_id,device_token_id,token_generation,next_attempt_at,expires_at)
        SELECT ?,id,generation,UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 24 HOUR)
        FROM notification_device_tokens WHERE member_id=? AND member_role=? AND active=TRUE
        """, notification.getId(), notification.getMemberId(), notification.getMemberRole());
  }
}
