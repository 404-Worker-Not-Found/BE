package com.workernotfound.notification.domain.notification.service;

import com.workernotfound.notification.domain.notification.entity.Notification;
import com.workernotfound.notification.domain.notification.event.NotificationEvent;
import com.workernotfound.notification.domain.notification.repository.*;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationEventService {
  private final NotificationEventRepository receipts;
  private final NotificationRepository notifications;
  private final Validator validator;

  @Transactional
  public void receive(NotificationEvent event) {
    validate(event);
    String fingerprint = fingerprint(event);
    if (!fingerprint.equals(receipts.record(event.eventId(), fingerprint)))
      throw new IllegalArgumentException("동일 이벤트 ID의 내용이 다릅니다.");
    if (notifications.existsByEventId(event.eventId())) return;
    if ("MatchConfirmed".equals(event.eventType()))
      notifications.save(notification(event, event.ownerMemberId(), "OWNER"));
    notifications.save(notification(event, event.workerMemberId(), "WORKER"));
  }

  private Notification notification(NotificationEvent event, Long memberId, String role) {
    return Notification.builder().eventId(event.eventId()).memberId(memberId).memberRole(role)
        .notificationType(event.eventType()).jobPostId(event.jobPostId())
        .matchingId("MatchConfirmed".equals(event.eventType()) ? event.matchingId() : null)
        .applicationId(event.applicationId()).occurredAt(event.occurredAt()).build();
  }

  private void validate(NotificationEvent event) {
    if (event == null || !validator.validate(event).isEmpty() || event.version() != 1)
      throw new IllegalArgumentException("지원하지 않거나 잘못된 알림 이벤트입니다.");
    if ("MatchConfirmed".equals(event.eventType())) {
      if (event.matchingId() == null || !event.aggregateId().equals(event.matchingId())
          || event.ownerMemberId() == null || event.ownerMemberId() <= 0)
        throw new IllegalArgumentException("매칭 알림의 필수 정보가 올바르지 않습니다.");
    } else if (!"ApplicationRejected".equals(event.eventType())
        || !event.aggregateId().equals(event.applicationId()) || !"REJECTED".equals(event.status()))
      throw new IllegalArgumentException("지원 결과 알림의 필수 정보가 올바르지 않습니다.");
  }

  private String fingerprint(NotificationEvent event) {
    var fields = new ArrayList<Object>(List.of(event.eventId(), event.eventType(),
        DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(event.occurredAt()), event.aggregateId(),
        event.revision(), event.version(), event.applicationId(), event.jobPostId(), event.workerMemberId()));
    if ("MatchConfirmed".equals(event.eventType())) {
      fields.add(event.matchingId()); fields.add(event.ownerMemberId());
    } else fields.add(event.status());
    var canonical = new StringBuilder();
    for (Object field : fields) {
      String value = field.toString();
      canonical.append(value.length()).append(':').append(value);
    }
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
  }
}
