package com.workernotfound.job.support;

import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// 운영 Clock과 같은 근무 일정 시간대를 쓴다. 실행 환경의 기본 시간대와 무관하게 경계를 검증하기 위해서다.
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

    @Bean
    @Primary
    public MutableClock mutableClock(@Value("${job.schedule.time-zone}") ZoneId scheduleTimeZone) {
        return new MutableClock(scheduleTimeZone);
    }
}
