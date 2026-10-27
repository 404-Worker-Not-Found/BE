package com.workernotfound.matching.domain.matching.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

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
  public String fingerprint() { return hash(eventId, factFingerprint()); }
  public String factFingerprint() {
    return hash("WORK_TERMINATION:v1", eventType, workId, matchingId, jobPostId, ownerMemberId,
        workerMemberId, actorMemberId, actorRole, status, occurredAt.truncatedTo(ChronoUnit.MICROS));
  }

  private String hash(Object... fields) {
    var canonical = new StringBuilder();
    for (Object field : fields) {
      String value = String.valueOf(field);
      canonical.append(value.length()).append(':').append(value);
    }
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
  }
}
