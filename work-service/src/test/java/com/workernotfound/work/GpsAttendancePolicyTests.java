package com.workernotfound.work;

import com.workernotfound.work.domain.work.dto.request.CheckInRequest;
import com.workernotfound.work.domain.work.entity.Work;
import com.workernotfound.work.domain.work.policy.*;
import com.workernotfound.work.global.config.AttendanceConfiguration;
import com.workernotfound.work.global.exception.BusinessException;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class GpsAttendancePolicyTests {
  private final LocalDateTime start = LocalDateTime.of(2026, 10, 4, 9, 0);
  private final Work work = Work.builder().workDate(start.toLocalDate()).startTime(start.toLocalTime())
      .endTime(LocalTime.of(18, 0)).latitude(new BigDecimal("37.5"))
      .longitude(new BigDecimal("127.0")).build();
  private final CheckInRequest nearby = new CheckInRequest(new BigDecimal("37.5005"), new BigDecimal("127"));

  @Test
  void radiusAndTimeWindowsCanChangeWithoutChangingServiceCode() {
    var normal = policy(100, 30, 30, false);
    assertThat(normal.checkIn(work, nearby, start).distanceMeters()).isBetween(55.0, 56.0);
    assertThatThrownBy(() -> policy(50, 30, 30, false).checkIn(work, nearby, start))
        .isInstanceOf(BusinessException.class);
    assertThatCode(() -> policy(60, 60, 0, false).checkIn(work, nearby, start.minusHours(1)))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> normal.checkIn(work, nearby, start.minusHours(1)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> policy(100, 60, 0, false).checkIn(work, nearby, start.plusSeconds(1)))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void earlyCompletionIsExplicitlyOptIn() {
    assertThatThrownBy(() -> policy(100, 30, 30, false).validateCompletion(work, start.plusHours(1)))
        .isInstanceOf(BusinessException.class);
    assertThatCode(() -> policy(100, 30, 30, true).validateCompletion(work, start.plusHours(1)))
        .doesNotThrowAnyException();
  }

  @Test
  void customPolicyBeanReplacesGpsPolicy() {
    for (Class<?>[] configurations : new Class<?>[][] {
        {AttendanceConfiguration.class, CustomPolicy.class}, {CustomPolicy.class, AttendanceConfiguration.class}}) {
    new ApplicationContextRunner().withUserConfiguration(configurations)
        .withPropertyValues("work.attendance.policy-type=custom", "work.attendance.radius-meters=100", "work.attendance.early-window=30m",
            "work.attendance.late-window=30m", "work.attendance.time-zone=Asia/Seoul",
            "work.attendance.completion-role=OWNER")
        .run(context -> {
          assertThat(context).hasSingleBean(AttendancePolicy.class);
          assertThat(context.getBean(AttendancePolicy.class)).isSameAs(context.getBean("customPolicy"));
          assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
        });
    }
  }

  private GpsAttendancePolicy policy(double radius, int earlyMinutes, int lateMinutes, boolean earlyCompletion) {
    return new GpsAttendancePolicy(new AttendanceProperties(radius, Duration.ofMinutes(earlyMinutes),
        Duration.ofMinutes(lateMinutes), ZoneId.of("Asia/Seoul"), earlyCompletion, CompletionRole.OWNER));
  }

  @Configuration(proxyBeanMethods = false)
  static class CustomPolicy {
    @Bean AttendancePolicy customPolicy() { return mock(AttendancePolicy.class); }
  }
}
