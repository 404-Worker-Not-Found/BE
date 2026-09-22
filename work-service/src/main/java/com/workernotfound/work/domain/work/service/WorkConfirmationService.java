package com.workernotfound.work.domain.work.service;

import com.workernotfound.work.domain.work.entity.Work;
import com.workernotfound.work.domain.work.entity.enums.WorkStatus;
import com.workernotfound.work.domain.work.event.MatchConfirmedEvent;
import com.workernotfound.work.domain.work.repository.*;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkConfirmationService {
  private final WorkRepository works;
  private final WorkCommandRepository commands;
  private final WorkConfirmationEventRepository events;
  private final Validator validator;

  @Transactional
  public void confirm(MatchConfirmedEvent event) {
    validate(event);
    Long activeId = commands.lockMatching(event.matchingId());
    Work work =
        works
            .findByIdForUpdate(Long.valueOf(event.workId()))
            .orElseThrow(() -> new IllegalStateException("확정 대상 근무가 없습니다."));
    validateSnapshot(work, event);
    recordEvent(event);
    // Canceled attempts must never be resurrected or modify a replacement work.
    if (work.getStatus() == WorkStatus.CANCELED) return;
    if (!Objects.equals(activeId, work.getId()))
      throw new IllegalStateException("확정 대상이 활성 근무와 일치하지 않습니다.");
    work.confirm(event.revision(), event.occurredAt());
  }

  private void validate(MatchConfirmedEvent event) {
    if (!validator.validate(event).isEmpty()
        || !"MatchConfirmed".equals(event.eventType())
        || !Integer.valueOf(1).equals(event.version())
        || !event.matchingId().equals(event.aggregateId()))
      throw new IllegalArgumentException("지원하지 않거나 유효하지 않은 근무 확정 이벤트입니다.");
  }

  private void validateSnapshot(Work work, MatchConfirmedEvent event) {
    if (!work.getMatchingId().equals(event.matchingId())
        || !work.getJobPostId().equals(event.jobPostId())
        || !work.getOwnerMemberId().equals(event.ownerMemberId())
        || !work.getWorkerMemberId().equals(event.workerMemberId())
        || !work.getPaymentId().equals(event.paymentId())
        || !work.getWorkDate().equals(event.workDate())
        || !work.getStartTime().equals(event.startTime())
        || !work.getEndTime().equals(event.endTime()))
      throw new IllegalArgumentException("근무 확정 이벤트의 스냅샷이 일치하지 않습니다.");
  }

  private void recordEvent(MatchConfirmedEvent event) {
    String fingerprint = fingerprint(event);
    String saved = events.record(event.eventId(), fingerprint);
    if (!fingerprint.equals(saved))
      throw new IllegalArgumentException("동일 이벤트 ID에 다른 내용이 전달되었습니다.");
  }

  private String fingerprint(MatchConfirmedEvent event) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(event.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
    }
  }
}
