package com.workernotfound.work.domain.work.service;

import com.workernotfound.work.domain.work.policy.AttendanceProperties;
import com.workernotfound.work.domain.work.repository.WorkCancellationRequestRepository;
import java.time.*;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "work.cancellation-expiry.enabled", havingValue = "true", matchIfMissing = true)
public class WorkCancellationExpiryScheduler {
  private final WorkCancellationRequestRepository requests;
  private final WorkCancellationExpiryService expiry;
  private final AttendanceProperties properties;
  private final Clock clock;

  @Scheduled(fixedDelayString = "${work.cancellation-expiry.poll-delay-ms:60000}",
      initialDelayString = "${work.cancellation-expiry.initial-delay-ms:0}")
  public void expire() {
    LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), properties.timeZone()).truncatedTo(ChronoUnit.MICROS);
    for (Long workId : requests.findExpiryCandidates(now.toLocalDate(), now.toLocalTime(), PageRequest.of(0, 100))) {
      try { expiry.expire(workId); }
      catch (RuntimeException exception) {
        log.warn("취소 요청 만료 재시도 필요: workId={}, type={}", workId, exception.getClass().getSimpleName());
      }
    }
  }
}
