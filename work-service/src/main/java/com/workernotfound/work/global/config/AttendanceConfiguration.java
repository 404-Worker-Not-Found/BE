package com.workernotfound.work.global.config;

import com.workernotfound.work.domain.work.policy.*;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration
@EnableConfigurationProperties(AttendanceProperties.class)
public class AttendanceConfiguration {
  @Bean
  @ConditionalOnProperty(name = "work.attendance.policy-type", havingValue = "gps", matchIfMissing = true)
  public AttendancePolicy attendancePolicy(AttendanceProperties properties) {
    return new GpsAttendancePolicy(properties);
  }

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  public Clock workClock(AttendanceProperties properties) { return Clock.system(properties.timeZone()); }
}
