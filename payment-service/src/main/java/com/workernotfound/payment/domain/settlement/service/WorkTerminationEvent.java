package com.workernotfound.payment.domain.settlement.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkTerminationEvent(String eventId, String eventType, long aggregateId, long revision,
    int version, LocalDateTime occurredAt, long workId, long matchingId, long jobPostId,
    long ownerMemberId, long workerMemberId, Long actorMemberId, String actorRole, String status) {
  @com.fasterxml.jackson.annotation.JsonIgnore
  public boolean isValid() {
    if (eventId == null || !eventId.matches("[!-~]{1,128}") || eventType == null || occurredAt == null
        || version != 1 || aggregateId != workId || workId <= 0 || matchingId <= 0 || jobPostId <= 0
        || ownerMemberId <= 0 || workerMemberId <= 0 || actorRole == null) return false;
    boolean participant = actorMemberId != null && (("OWNER".equals(actorRole) && actorMemberId == ownerMemberId)
        || ("WORKER".equals(actorRole) && actorMemberId == workerMemberId));
    return switch (eventType) {
      case "WorkCompleted" -> revision == 3 && "COMPLETED".equals(status) && participant;
      case "WorkNoShow" -> revision == 4 && "NO_SHOW".equals(status) && "SYSTEM".equals(actorRole) && actorMemberId == null;
      case "WorkCanceled" -> revision == 5 && "CANCELED".equals(status) && participant;
      default -> false;
    };
  }

  @com.fasterxml.jackson.annotation.JsonIgnore
  public boolean isCompleted() { return "WorkCompleted".equals(eventType); }
  public String fingerprint() { return EventFingerprint.of(eventId, factFingerprint()); }
  public String factFingerprint() {
    return EventFingerprint.of("WORK_TERMINATION:v1", eventType, workId, matchingId, jobPostId, ownerMemberId,
        workerMemberId, actorMemberId, actorRole, status, occurredAt.truncatedTo(ChronoUnit.MICROS));
  }

}
