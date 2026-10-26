package com.workernotfound.matching.global.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 지원 취소 기한 경계를 테스트할 수 있도록 현재 시각은 Clock 빈에서 얻는다.
// 근무 일시는 점주가 입력한 근무 일정 시간대의 로컬 시각이므로 실행 환경의 기본 시간대와 무관하게 그 시간대로 읽는다.
@Configuration
public class ClockConfig {

	@Bean
	public Clock clock(@Value("${matching.schedule.time-zone}") ZoneId scheduleTimeZone) {
		return Clock.system(scheduleTimeZone);
	}
}
