package com.workernotfound.work.global.config;

import com.workernotfound.work.domain.work.service.WorkNoShowReconciler;
import com.workernotfound.work.domain.work.service.WorkNoShowScheduler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "work.no-show.enabled", havingValue = "true", matchIfMissing = true)
public class WorkNoShowConfiguration {
  @Bean
  @ConditionalOnProperty(name = "work.attendance.policy-type", havingValue = "gps", matchIfMissing = true)
  public WorkNoShowScheduler workNoShowScheduler(WorkNoShowReconciler reconciler) {
    return new WorkNoShowScheduler(reconciler);
  }
}
