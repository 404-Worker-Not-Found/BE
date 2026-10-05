package com.workernotfound.job.domain.job.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 결제 금액과 모집 조건을 정하는 공고 필드. 점주의 결제 조건 변경은 이 필드만 바꾼다.
 *
 * <p>근무 일시·익일 여부·기본 시급·시간당 추가 시급·모집 인원은 예치 금액을 정하고, 지원 마감은 근무 시작 이전이어야 하므로 함께
 * 바꾼다. 가게·제목·설명·위치·긴급도는 결제 조건이 아니며 이 경로로 바꾸지 않는다.
 */
public record JobPaymentTerms(
        LocalDate workDate,
        LocalTime startTime,
        LocalTime endTime,
        boolean endTimeNextDay,
        Integer baseHourlyWage,
        Integer extraWage,
        Integer recruitCount,
        LocalDateTime applicationDeadline
) {
}
