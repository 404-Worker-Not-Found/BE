package com.workernotfound.notification.domain.notification.service;

import com.workernotfound.notification.domain.notification.entity.Notification;
import com.workernotfound.notification.domain.notification.event.WorkNotificationEvent;
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
public class WorkNotificationEventService {
  private final NotificationEventRepository receipts;
  private final NotificationRepository notifications;
  private final Validator validator;

  @Transactional
  public void receive(WorkNotificationEvent event) {
    validate(event);
    String fingerprint = fingerprint(event);
    if (!fingerprint.equals(receipts.record(event.eventId(), fingerprint)))
      throw new IllegalArgumentException("동일 근무 이벤트 ID의 내용이 다릅니다.");
    if (notifications.existsByEventId(event.eventId())) return;
    boolean forOwner = "WORKER".equals(event.actorRole());
    notifications.save(Notification.builder().eventId(event.eventId())
        .memberId(forOwner ? event.ownerMemberId() : event.workerMemberId())
        .memberRole(forOwner ? "OWNER" : "WORKER").notificationType(event.eventType())
        .jobPostId(event.jobPostId()).matchingId(event.matchingId()).workId(event.workId())
        .occurredAt(event.occurredAt()).build());
  }

  private void validate(WorkNotificationEvent event) {
    if (event == null || !validator.validate(event).isEmpty() || event.version() != 1
        || !event.aggregateId().equals(event.workId())
        || event.endTimeNextDay() != !event.endTime().isAfter(event.startTime()))
      throw new IllegalArgumentException("지원하지 않거나 잘못된 근무 알림 이벤트입니다.");
    boolean owner = "OWNER".equals(event.actorRole()) && event.actorMemberId().equals(event.ownerMemberId());
    boolean worker = "WORKER".equals(event.actorRole()) && event.actorMemberId().equals(event.workerMemberId());
    boolean valid = switch (event.eventType()) {
      case "WorkCheckedIn" -> worker && event.revision() == 1 && "CHECKED_IN".equals(event.status());
      case "WorkStarted" -> owner && event.revision() == 2 && "IN_PROGRESS".equals(event.status());
      case "WorkCompleted" -> (owner || worker) && event.revision() == 3 && "COMPLETED".equals(event.status());
      default -> false;
    };
    if (!valid) throw new IllegalArgumentException("근무 전이와 처리자 정보가 올바르지 않습니다.");
  }

  private String fingerprint(WorkNotificationEvent event) {
    List<Object> fields = List.of("WORK:v1", event.eventId(), event.eventType(), event.aggregateId(),
        event.revision(), event.version(), DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(event.occurredAt()),
        event.workId(), event.matchingId(), event.jobPostId(), event.ownerMemberId(), event.workerMemberId(),
        event.actorMemberId(), event.actorRole(), event.status(), event.workDate(), event.startTime(),
        event.endTime(), event.endTimeNextDay());
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
