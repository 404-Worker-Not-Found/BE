package com.workernotfound.matching.support;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

// job-service 응답(지원 승인·자리 예약)을 흉내 낼 때 쓴다. job-service는 근무 일정 시간대로 시각을 만들므로
// application.yaml의 matching.schedule.time-zone 기본값과 같은 시간대로 만든다. matching-service 내부 시각에는 쓰지 않는다.
public final class TestSchedule {

	public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	private TestSchedule() {
	}

	public static LocalDateTime now() {
		return LocalDateTime.now(ZONE);
	}

	public static LocalDate today() {
		return LocalDate.now(ZONE);
	}
}
