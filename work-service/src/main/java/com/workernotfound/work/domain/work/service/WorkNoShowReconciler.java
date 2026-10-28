package com.workernotfound.work.domain.work.service;

import com.workernotfound.work.domain.work.policy.AttendanceProperties;
import com.workernotfound.work.domain.work.repository.WorkRepository;
import java.time.*;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkNoShowReconciler {
  private final WorkRepository works;
  private final WorkNoShowCommandService commands;
  private final AttendanceProperties properties;
  private final Clock clock;

  public void reconcile() {
    LocalDateTime cutoff = LocalDateTime.ofInstant(clock.instant(), properties.timeZone())
        .truncatedTo(ChronoUnit.MICROS).minus(properties.lateWindow());
    for (Long id : works.findNoShowCandidates(cutoff.toLocalDate(), cutoff.toLocalTime(), PageRequest.of(0, 100))) {
      try {
        commands.markNoShow(id);
      } catch (RuntimeException exception) {
        log.warn("노쇼 판정 재시도 필요: workId={}, type={}", id, exception.getClass().getSimpleName());
      }
    }
  }
}
