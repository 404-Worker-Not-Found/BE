package com.workernotfound.work.domain.work.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;

@RequiredArgsConstructor
public class WorkNoShowScheduler {
  private final WorkNoShowReconciler reconciler;

  @Scheduled(fixedDelayString = "${work.no-show.poll-delay-ms:60000}",
      initialDelayString = "${work.no-show.initial-delay-ms:0}")
  public void reconcile() {
    reconciler.reconcile();
  }
}
