package com.workernotfound.work.domain.work.service;

import com.workernotfound.work.domain.outbox.WorkEventRecorder;
import com.workernotfound.work.domain.work.entity.*;
import com.workernotfound.work.domain.work.entity.enums.WorkCancellationStatus;
import com.workernotfound.work.domain.work.policy.AttendanceProperties;
import com.workernotfound.work.domain.work.repository.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class WorkCancellationExpiryService {
  private final WorkRepository works;
  private final WorkCancellationRequestRepository requests;
  private final WorkEventRecorder events;
  private final AttendanceProperties properties;
  private final Clock clock;

  @Transactional
  public void expire(Long workId) {
    works.findByIdForUpdate(workId).ifPresent(work -> expirePending(work,
        LocalDateTime.ofInstant(clock.instant(), properties.timeZone()).truncatedTo(ChronoUnit.MICROS)));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void expirePending(Work work, LocalDateTime now) {
    if (work.canRequestCancellation(now)) return;
    requests.findByWorkIdAndStatus(work.getId(), WorkCancellationStatus.PENDING).ifPresent(request -> {
      request.resolve(WorkCancellationStatus.EXPIRED, null, now);
      events.recordCancellation(work, request, null, "SYSTEM", now);
    });
  }
}
