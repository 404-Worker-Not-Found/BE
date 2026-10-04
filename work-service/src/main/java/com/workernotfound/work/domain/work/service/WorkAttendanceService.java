package com.workernotfound.work.domain.work.service;

import com.workernotfound.work.domain.work.dto.request.CheckInRequest;
import com.workernotfound.work.domain.outbox.WorkEventRecorder;
import com.workernotfound.work.domain.work.dto.response.*;
import com.workernotfound.work.domain.work.entity.Work;
import com.workernotfound.work.domain.work.exception.WorkErrorCode;
import com.workernotfound.work.domain.work.policy.AttendancePolicy;
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
public class WorkAttendanceService {
  private final WorkRepository works;
  private final WorkAttendanceHistoryRepository history;
  private final AttendancePolicy policy;
  private final AttendanceProperties properties;
  private final Clock clock;
  private final WorkEventRecorder events;

  @Transactional
  public WorkResponse checkIn(AuthenticatedMember member, Long workId, CheckInRequest request) {
    Work work = lockWork(member, workId, "WORKER");
    if (work.getCheckedInAt() != null) return WorkResponse.from(work);
    LocalDateTime now = now();
    var decision = policy.checkIn(work, request, now);
    String previous = work.getStatus().name();
    work.checkIn(now, decision.distanceMeters(), decision.policy());
    history.record(workId, previous, work.getStatus().name(), member.memberId(), now);
    events.record(work, member.memberId(), member.role(), now);
    return WorkResponse.from(work);
  }

  @Transactional
  public WorkResponse start(AuthenticatedMember member, Long workId) {
    Work work = lockWork(member, workId, "OWNER");
    if (work.getStartedAt() != null) return WorkResponse.from(work);
    LocalDateTime now = now();
    policy.validateStart(work, now);
    String previous = work.getStatus().name();
    work.start(now);
    history.record(workId, previous, work.getStatus().name(), member.memberId(), now);
    events.record(work, member.memberId(), member.role(), now);
    return WorkResponse.from(work);
  }

  @Transactional
  public WorkResponse complete(AuthenticatedMember member, Long workId) {
    Work work = lockWork(member, workId, policy.completionRole().name());
    if (work.getCompletedAt() != null) return WorkResponse.from(work);
    LocalDateTime now = now();
    policy.validateCompletion(work, now);
    String previous = work.getStatus().name();
    work.complete(now);
    history.record(workId, previous, work.getStatus().name(), member.memberId(), now);
    events.record(work, member.memberId(), member.role(), now);
    return WorkResponse.from(work);
  }

  public AttendancePolicyResponse getPolicy() { return policy.describe(); }

  private Work lockWork(AuthenticatedMember member, Long id, String role) {
    if (!role.equals(member.role())) throw new BusinessException(WorkErrorCode.WORK_NOT_FOUND);
    return works.findConfirmedForUpdate(id, member.memberId(), role)
        .orElseThrow(() -> new BusinessException(WorkErrorCode.WORK_NOT_FOUND));
  }

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant(), properties.timeZone()).truncatedTo(ChronoUnit.MICROS);
  }
}
