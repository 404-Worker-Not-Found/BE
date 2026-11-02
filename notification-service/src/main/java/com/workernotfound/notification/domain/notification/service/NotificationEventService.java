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

  static final String MATCH_CONFIRMED = "MatchConfirmed";
  static final String APPLICATION_REJECTED = "ApplicationRejected";
  static final String MATCHING_PROPOSED = "MatchingProposed";
  static final String CANDIDATES_EXHAUSTED = "MatchingCandidatesExhausted";

  @Transactional
  public void receive(NotificationEvent event) {
    validate(event);
    String fingerprint = fingerprint(event);
    if (!fingerprint.equals(receipts.record(event.eventId(), fingerprint)))
      throw new IllegalArgumentException("동일 이벤트 ID의 내용이 다릅니다.");
    if (notifications.existsByEventId(event.eventId())) return;
    switch (event.eventType()) {
      case MATCH_CONFIRMED -> {
        notifications.save(notification(event, event.ownerMemberId(), "OWNER", event.matchingId(), event.applicationId()));
        notifications.save(notification(event, event.workerMemberId(), "WORKER", event.matchingId(), event.applicationId()));
      }
      case MATCHING_PROPOSED ->
          notifications.save(notification(event, event.workerMemberId(), "WORKER", event.matchingId(), event.applicationId()));
      case CANDIDATES_EXHAUSTED -> notifications.save(notification(event, event.ownerMemberId(), "OWNER", null, null));
      default -> notifications.save(notification(event, event.workerMemberId(), "WORKER", null, event.applicationId()));
    }
  }

  private Notification notification(NotificationEvent event, Long memberId, String role, Long matchingId,
      Long applicationId) {
    return Notification.builder().eventId(event.eventId()).memberId(memberId).memberRole(role)
        .notificationType(event.eventType()).jobPostId(event.jobPostId())
        .matchingId(matchingId).applicationId(applicationId).occurredAt(event.occurredAt()).build();
  }

  private void validate(NotificationEvent event) {
    if (event == null || !validator.validate(event).isEmpty() || event.version() != 1)
      throw new IllegalArgumentException("지원하지 않거나 잘못된 알림 이벤트입니다.");
    boolean valid = switch (event.eventType()) {
      case MATCH_CONFIRMED, MATCHING_PROPOSED -> event.aggregateId().equals(event.matchingId())
          && event.applicationId() != null && event.workerMemberId() != null && event.ownerMemberId() != null;
      case APPLICATION_REJECTED -> event.aggregateId().equals(event.applicationId())
          && event.workerMemberId() != null && "REJECTED".equals(event.status());
      case CANDIDATES_EXHAUSTED -> event.aggregateId().equals(event.jobPostId()) && event.ownerMemberId() != null
          && event.remainingSeats() != null && event.applicationId() == null && event.workerMemberId() == null
          && event.matchingId() == null;
      default -> false;
    };
    if (!valid) throw new IllegalArgumentException("알림 이벤트의 필수 정보가 올바르지 않습니다.");
  }

  // 기존 MatchConfirmed·ApplicationRejected의 필드 순서는 이미 저장된 수신 기록과 같아야 하므로 바꾸지 않는다.
  private String fingerprint(NotificationEvent event) {
    var fields = new ArrayList<Object>(List.of(event.eventId(), event.eventType(),
        DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(event.occurredAt()), event.aggregateId(),
        event.revision(), event.version()));
    switch (event.eventType()) {
      case MATCH_CONFIRMED, MATCHING_PROPOSED -> fields.addAll(List.of(event.applicationId(), event.jobPostId(),
          event.workerMemberId(), event.matchingId(), event.ownerMemberId()));
      case CANDIDATES_EXHAUSTED -> fields.addAll(List.of(event.jobPostId(), event.ownerMemberId(),
          event.remainingSeats()));
      default -> fields.addAll(List.of(event.applicationId(), event.jobPostId(), event.workerMemberId(),
          event.status()));
    }
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
