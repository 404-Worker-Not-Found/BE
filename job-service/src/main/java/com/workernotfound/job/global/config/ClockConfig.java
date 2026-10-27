package com.workernotfound.job.global.config;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;

// 만료 시각 경계를 테스트할 수 있도록 현재 시각은 Clock 빈에서 얻는다.
// 지원 마감·근무 일시는 점주가 입력한 근무 일정 시간대의 로컬 시각이므로, job-service의 모든 현재 시각을
// 실행 환경의 기본 시간대와 무관하게 그 시간대로 읽는다. 생성·수정 시각(JPA auditing)도 같은 Clock을 쓴다.
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock(@Value("${job.schedule.time-zone}") ZoneId scheduleTimeZone) {
        return Clock.system(scheduleTimeZone);
    }

    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(LocalDateTime.now(clock));
    }
}
