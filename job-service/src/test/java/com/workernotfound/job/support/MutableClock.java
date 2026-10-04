package com.workernotfound.job.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

// 시간 경계 테스트용 시계. 고정하지 않으면 시스템 시각을 따른다.
public class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant fixedInstant;

    public MutableClock(ZoneId zone) {
        this.zone = zone;
    }

    public void fixAt(Instant instant) {
        this.fixedInstant = instant;
    }

    public void fixAtNow() {
        this.fixedInstant = Instant.now();
    }

    public void advance(Duration duration) {
        if (fixedInstant == null) {
            fixAtNow();
        }
        this.fixedInstant = fixedInstant.plus(duration);
    }

    public void reset() {
        this.fixedInstant = null;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("테스트 시계는 시간대 변경을 지원하지 않습니다.");
    }

    @Override
    public Instant instant() {
        Instant instant = fixedInstant;
        return instant == null ? Instant.now() : instant;
    }
}
