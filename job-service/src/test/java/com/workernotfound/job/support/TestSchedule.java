package com.workernotfound.job.support;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

// 테스트 픽스처의 공고 일정·기준 시각을 운영 Clock과 같은 근무 일정 시간대로 만든다.
// application.yaml의 job.schedule.time-zone 기본값과 같다. 실행 환경의 기본 시간대와 무관하게 경계를 맞추기 위해 쓴다.
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
