package com.workernotfound.work.domain.work.service;

import com.workernotfound.work.domain.outbox.WorkEventRecorder;
import com.workernotfound.work.domain.work.entity.Work;
import com.workernotfound.work.domain.work.entity.enums.WorkStatus;
import com.workernotfound.work.domain.work.policy.AttendanceProperties;
import com.workernotfound.work.domain.work.repository.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkNoShowCommandService {
  private final WorkRepository works;
  private final WorkAttendanceHistoryRepository history;
  private final WorkEventRecorder events;
  private final AttendanceProperties properties;
  private final Clock clock;
  private final WorkCancellationExpiryService cancellationExpiry;

  @Transactional
  public boolean markNoShow(Long workId) {
    Work work = works.findByIdForUpdate(workId).orElse(null);
    LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), properties.timeZone())
        .truncatedTo(ChronoUnit.MICROS);
    if (!isCandidate(work) || !now.isAfter(work.scheduledStart().plus(properties.lateWindow()))) return false;
    cancellationExpiry.expirePending(work, now);
    work.markNoShow(now, work.scheduledStart().plus(properties.lateWindow()));
    history.recordNoShow(workId, now);
    events.record(work, null, "SYSTEM", now);
    return true;
  }

  private boolean isCandidate(Work work) {
    return work != null && work.getConfirmedAt() != null && work.getStatus() == WorkStatus.SCHEDULED
        && work.getCheckedInAt() == null && work.getLatitude() != null && work.getLongitude() != null;
  }
}
