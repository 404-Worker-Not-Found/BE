package com.workernotfound.matching.domain.matching.service;

import com.workernotfound.matching.domain.application.entity.*;
import com.workernotfound.matching.domain.application.entity.enums.*;
import com.workernotfound.matching.domain.application.event.ApplicationEvent;
import com.workernotfound.matching.domain.application.repository.*;
import com.workernotfound.matching.domain.matching.entity.*;
import com.workernotfound.matching.domain.matching.entity.enums.*;
import com.workernotfound.matching.domain.matching.event.WorkTerminationEvent;
import com.workernotfound.matching.domain.matching.repository.*;
import com.workernotfound.matching.domain.outbox.service.OutboxEventCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class WorkTerminationService {
  private final MatchingRepository matchings;
  private final ApplicationRepository applications;
  private final MatchingConfirmationSagaRepository sagas;
  private final MatchingStatusHistoryRepository matchingHistory;
  private final ApplicationStatusHistoryRepository applicationHistory;
  private final WorkTerminationRepository receipts;
  private final OutboxEventCommandService outbox;
  private final org.springframework.context.ApplicationEventPublisher eventPublisher;

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public String receive(WorkTerminationEvent event) {
    if (!event.isValid()) throw new IllegalArgumentException("잘못된 근무 종료 이벤트입니다.");
    var receipt = receipts.findReceipt(event.eventId());
    if (receipt.isPresent()) {
      if (!receipt.get().fingerprint().equals(event.fingerprint())) throw new IllegalArgumentException("같은 eventId의 내용이 다릅니다.");
      return receipt.get().result();
    }
    var target = matchings.findLockTargetById(event.matchingId())
        .orElseThrow(() -> new IllegalStateException("선행 매칭 확정을 기다립니다."));
    Application application = applications.findByIdForUpdate(target.applicationId()).orElseThrow();
    Matching matching = matchings.findByIdForUpdate(event.matchingId()).orElseThrow();
    if (!sameParticipants(matching, event)) return receipts.recordReceipt(event, "REJECTED_PARTICIPANTS");
    var fact = receipts.findFact(event.workId());
    if (fact.isPresent()) return receipts.recordReceipt(event,
        fact.get().equals(event.factFingerprint()) ? "ALREADY_TERMINATED" : "REJECTED_WORK_CONFLICT");
    if (matching.getStatus() == MatchingStatus.PENDING) throw new IllegalStateException("선행 매칭 확정을 기다립니다.");
    if (matching.getStatus() != MatchingStatus.CONFIRMED || application.getStatus() != ApplicationStatus.SELECTED)
      return receipts.recordReceipt(event, "REJECTED_MATCHING_STATE");
    var saga = sagas.findByMatchingIdForUpdate(matching.getId()).orElseThrow();
    if (saga.getStatus() != MatchingConfirmationSagaStatus.COMPLETED) throw new IllegalStateException("미완료 확정 Saga입니다.");
    if (!Long.toString(event.workId()).equals(saga.getWorkId())) return receipts.recordReceipt(event, "REJECTED_WORK_MISMATCH");
    finish(matching, application, event);
    receipts.recordFact(event);
    return receipts.recordReceipt(event, "APPLIED");
  }

  private void finish(Matching matching, Application application, WorkTerminationEvent event) {
    matching.finishWork(event.isCompleted());
    matchingHistory.saveAndFlush(MatchingStatusHistory.builder().matching(matching).fromStatus(MatchingStatus.CONFIRMED)
        .toStatus(matching.getStatus()).actorType(MatchingActorType.valueOf(event.actorRole())).actorMemberId(event.actorMemberId())
        .reasonCode(event.eventType()).revision(matching.getRevision()).changedAt(event.occurredAt()).build());
    if (event.isCompleted()) return;
    application.cancelSelectedWork();
    applicationHistory.saveAndFlush(ApplicationStatusHistory.builder().application(application).fromStatus(ApplicationStatus.SELECTED)
        .toStatus(ApplicationStatus.CANCELED).actorType(ApplicationActorType.valueOf(event.actorRole())).actorMemberId(event.actorMemberId())
        .reasonCode(event.eventType()).revision(application.getRevision()).changedAt(event.occurredAt()).build());
    var canceled = ApplicationEvent.canceled(application, "work-termination-" + event.workId(), event.occurredAt());
    outbox.saveApplicationEvent(canceled);
    eventPublisher.publishEvent(canceled);
  }

  private boolean sameParticipants(Matching matching, WorkTerminationEvent event) {
    return matching.getJobPostId() == event.jobPostId() && matching.getOwnerMemberId() == event.ownerMemberId()
        && matching.getWorkerMemberId() == event.workerMemberId();
  }
}
