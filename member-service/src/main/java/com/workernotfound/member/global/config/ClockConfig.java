package com.workernotfound.member.global.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 리뷰 작성 기한 경계를 테스트할 수 있도록 현재 시각은 Clock 빈에서 얻는다.
// 근무 완료 시각은 work-service가 근무 일정 시간대로 만든 값이므로 같은 시간대로 읽는다.
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

	@Bean
	public Clock clock(@Value("${member.schedule.time-zone}") ZoneId scheduleTimeZone) {
		return Clock.system(scheduleTimeZone);
	}
}
