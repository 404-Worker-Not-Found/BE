package com.workernotfound.work.domain.work.service;

import com.workernotfound.work.domain.outbox.WorkEventRecorder;
import com.workernotfound.work.domain.work.entity.*;
import com.workernotfound.work.domain.work.entity.enums.WorkCancellationStatus;
import com.workernotfound.work.domain.work.exception.WorkErrorCode;
import com.workernotfound.work.domain.work.policy.AttendanceProperties;
import com.workernotfound.work.domain.work.repository.*;
import com.workernotfound.work.global.exception.BusinessException;
import com.workernotfound.work.global.security.AuthenticatedMember;
import java.time.*;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkCancellationCommandService {
  private final WorkRepository works;
  private final WorkCancellationRequestRepository requests;
  private final WorkAttendanceHistoryRepository history;
  private final WorkCancellationExpiryService expiry;
  private final WorkEventRecorder events;
  private final AttendanceProperties properties;
  private final Clock clock;

  @Transactional
  public WorkCancellationRequest request(AuthenticatedMember member, Long workId, String key, String reason) {
    Work work = lockWork(member, workId);
    var previous = requests.findByWorkIdAndRequesterMemberIdAndRequestKey(workId, member.memberId(), key);
    if (previous.isPresent()) {
      if (!previous.get().getReason().equals(reason.strip())) throw new BusinessException(WorkErrorCode.COMMAND_CONFLICT);
      return previous.get();
    }
    LocalDateTime now = now();
    if (!work.canRequestCancellation(now)) throw new BusinessException(WorkErrorCode.CANCELLATION_EXPIRED);
    if (requests.findByWorkIdAndStatus(workId, WorkCancellationStatus.PENDING).isPresent())
      throw new BusinessException(WorkErrorCode.CANCELLATION_PENDING);
    var request = requests.saveAndFlush(WorkCancellationRequest.builder().workId(workId)
        .requesterMemberId(member.memberId()).requesterRole(member.role()).requestKey(key)
        .reason(reason.strip()).createdAt(now).build());
    events.recordCancellation(work, request, member.memberId(), member.role(), now);
    return request;
  }

  @Transactional
  public WorkCancellationRequest respond(AuthenticatedMember member, Long workId, Long requestId, boolean approve) {
    Work work = lockWork(member, workId);
    var request = requireRequest(workId, requestId);
    if (request.getRequesterMemberId().equals(member.memberId())) throw new BusinessException(WorkErrorCode.CANCELLATION_NOT_FOUND);
    var result = approve ? WorkCancellationStatus.APPROVED : WorkCancellationStatus.REJECTED;
    if (request.getStatus() == result) return request;
    return resolve(work, request, member, result);
  }

  @Transactional
  public WorkCancellationRequest withdraw(AuthenticatedMember member, Long workId, Long requestId) {
    Work work = lockWork(member, workId);
    var request = requireRequest(workId, requestId);
    if (!request.getRequesterMemberId().equals(member.memberId())) throw new BusinessException(WorkErrorCode.CANCELLATION_NOT_FOUND);
    if (request.getStatus() == WorkCancellationStatus.WITHDRAWN) return request;
    return resolve(work, request, member, WorkCancellationStatus.WITHDRAWN);
  }

  private WorkCancellationRequest resolve(Work work, WorkCancellationRequest request,
      AuthenticatedMember member, WorkCancellationStatus result) {
    if (request.getStatus() == WorkCancellationStatus.EXPIRED) return request;
    if (request.getStatus() != WorkCancellationStatus.PENDING) throw new BusinessException(WorkErrorCode.CANCELLATION_STATE_CONFLICT);
    LocalDateTime now = now();
    expiry.expirePending(work, now);
    if (request.getStatus() == WorkCancellationStatus.EXPIRED) return request;
    request.resolve(result, member.memberId(), now);
    if (result == WorkCancellationStatus.APPROVED) {
      work.cancelByAgreement(now);
      history.record(work.getId(), "SCHEDULED", "CANCELED", member.memberId(), now);
      events.record(work, member.memberId(), member.role(), now);
    } else events.recordCancellation(work, request, member.memberId(), member.role(), now);
    return request;
  }

  private Work lockWork(AuthenticatedMember member, Long id) {
    return works.findConfirmedForUpdate(id, member.memberId(), member.role())
        .orElseThrow(() -> new BusinessException(WorkErrorCode.WORK_NOT_FOUND));
  }

  private WorkCancellationRequest requireRequest(Long workId, Long requestId) {
    return requests.findByIdAndWorkId(requestId, workId)
        .orElseThrow(() -> new BusinessException(WorkErrorCode.CANCELLATION_NOT_FOUND));
  }

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant(), properties.timeZone()).truncatedTo(ChronoUnit.MICROS);
  }
}
