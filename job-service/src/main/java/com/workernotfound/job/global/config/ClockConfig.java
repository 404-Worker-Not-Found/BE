package com.workernotfound.job.global.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 만료 시각 경계를 테스트할 수 있도록 현재 시각은 Clock 빈에서 얻는다.
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
