package com.workernotfound.notification.domain.notification.service;

import com.workernotfound.notification.domain.notification.entity.Notification;
import com.workernotfound.notification.domain.notification.repository.*;
import com.workernotfound.notification.global.exception.*;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FundingIncidentService {
  private final NotificationEventRepository receipts;
  private final NotificationRepository notifications;
  public record Command(@NotNull @Positive Long jobPostId,@NotNull @Positive Long memberId,
      @NotNull @Pattern(regexp="OWNER|WORKER") String memberRole,@Positive Long matchingId,@Positive Long workId,
      @NotNull LocalDateTime occurredAt) {}

  @Transactional
  public void receive(String key,Command command) {
    if (("OWNER".equals(command.memberRole()) && (command.workId()!=null || command.matchingId()!=null))
        || ("WORKER".equals(command.memberRole()) && (command.workId()==null || command.matchingId()==null)))
      throw new BusinessException(GlobalErrorCode.INVALID_REQUEST);
    String eventId="incident:"+key;
    String fingerprint=fingerprint(command);
    if(!fingerprint.equals(receipts.record(eventId,fingerprint))) throw new BusinessException(GlobalErrorCode.INVALID_REQUEST);
    if(notifications.existsByEventId(eventId)) return;
    notifications.saveAndFlush(Notification.builder().eventId(eventId).memberId(command.memberId()).memberRole(command.memberRole())
        .notificationType("FUNDING_REVIEW_REQUIRED").jobPostId(command.jobPostId()).matchingId(command.matchingId()).workId(command.workId())
        .occurredAt(command.occurredAt()).build());
  }

  private String fingerprint(Command c) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
          ("FUNDING:v1:"+c.jobPostId()+":"+c.memberId()+":"+c.memberRole()+":"+c.matchingId()+":"+c.workId()+":"+c.occurredAt()).getBytes(StandardCharsets.UTF_8)));
    } catch(NoSuchAlgorithmException e) {throw new IllegalStateException("SHA-256 unavailable",e);}
  }
}
