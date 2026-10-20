package com.workernotfound.payment.domain.settlement.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;

/**
 * 정산 예약에 쓰는 WorkCompleted v1의 근무 완료 사실. 금액·지급 계좌는 포함하지 않는다.
 *
 * <p>completedAt은 이벤트 occurredAt(근무 시간대 로컬 시각)이며 저장 정밀도에 맞춰 마이크로초로 자른다.
 */
public record WorkCompletion(String eventId, Long workId, Long matchingId, Long jobPostId,
    Long ownerMemberId, Long workerMemberId, LocalDateTime completedAt) {

  public WorkCompletion {
    completedAt = completedAt == null ? null : completedAt.truncatedTo(ChronoUnit.MICROS);
  }

  public boolean isComplete() {
    return eventId != null && !eventId.isBlank() && eventId.length() <= 64 && completedAt != null
        && Stream.of(workId, matchingId, jobPostId, ownerMemberId, workerMemberId)
            .allMatch(id -> id != null && id > 0);
  }

  public String fingerprint(long revision) {
    return EventFingerprint.of("WorkCompleted:v1", eventId, workId, revision, matchingId, jobPostId,
        ownerMemberId, workerMemberId, completedAt);
  }
}
