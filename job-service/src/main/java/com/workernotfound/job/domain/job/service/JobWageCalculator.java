package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.global.exception.BusinessException;
import java.time.LocalTime;
import org.springframework.stereotype.Component;

/**
 * 공고 1인 예정 급여와 전체 예치 예정액을 계산한다.
 *
 * <p>초기 계산 기준: 근무 분 × (기본 시급 + 시간당 추가 시급) / 60을 정수 KRW로 내림한다.
 * 휴게시간은 공고에 입력값이 없어 차감하지 않는다. 전체 예치 예정액은 내림한 1인 금액 × 모집 인원이다.
 */
@Component
public class JobWageCalculator {

    private static final long MINUTES_PER_HOUR = 60;
    private static final long MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR;
    // payment-service 금액 컬럼 DECIMAL(19,2)의 정수부 최대값이다.
    private static final long MAX_TOTAL_WAGE = 99_999_999_999_999_999L;

    public long calculateWagePerWorker(JobPost jobPost) {
        return calculateWagePerWorker(
                jobPost.getStartTime(),
                jobPost.getEndTime(),
                jobPost.isEndTimeNextDay(),
                jobPost.getBaseHourlyWage(),
                jobPost.getExtraWage()
        );
    }

    public long calculateTotalExpectedWage(JobPost jobPost) {
        return calculateTotalExpectedWage(calculateWagePerWorker(jobPost), jobPost.getRecruitCount());
    }

    public long calculateWagePerWorker(
            LocalTime startTime,
            LocalTime endTime,
            boolean isEndTimeNextDay,
            Integer baseHourlyWage,
            Integer extraWage
    ) {
        long workMinutes = calculateWorkMinutes(startTime, endTime, isEndTimeNextDay);
        long hourlyWage = toHourlyWage(baseHourlyWage, extraWage);
        long wage = workMinutes * hourlyWage / MINUTES_PER_HOUR;
        if (wage <= 0 || wage > MAX_TOTAL_WAGE) {
            throw new BusinessException(JobErrorCode.INVALID_WAGE_AMOUNT);
        }
        return wage;
    }

    public long calculateTotalExpectedWage(long wagePerWorker, Integer recruitCount) {
        if (recruitCount == null || recruitCount <= 0) {
            throw new BusinessException(JobErrorCode.INVALID_WAGE_AMOUNT);
        }
        try {
            long total = Math.multiplyExact(wagePerWorker, recruitCount.longValue());
            if (total > MAX_TOTAL_WAGE) {
                throw new BusinessException(JobErrorCode.INVALID_WAGE_AMOUNT);
            }
            return total;
        } catch (ArithmeticException exception) {
            throw new BusinessException(JobErrorCode.INVALID_WAGE_AMOUNT);
        }
    }

    long calculateWorkMinutes(LocalTime startTime, LocalTime endTime, boolean isEndTimeNextDay) {
        if (startTime == null || endTime == null || !isWholeMinute(startTime) || !isWholeMinute(endTime)) {
            throw invalidWorkTime();
        }
        long startMinute = startTime.toSecondOfDay() / MINUTES_PER_HOUR;
        long endMinute = endTime.toSecondOfDay() / MINUTES_PER_HOUR;
        long workMinutes = endMinute - startMinute + (isEndTimeNextDay ? MINUTES_PER_DAY : 0);
        if (workMinutes <= 0 || workMinutes > MINUTES_PER_DAY) {
            throw invalidWorkTime();
        }
        return workMinutes;
    }

    private long toHourlyWage(Integer baseHourlyWage, Integer extraWage) {
        if (baseHourlyWage == null || baseHourlyWage <= 0 || (extraWage != null && extraWage < 0)) {
            throw new BusinessException(JobErrorCode.INVALID_WAGE_AMOUNT);
        }
        return baseHourlyWage.longValue() + (extraWage == null ? 0 : extraWage.longValue());
    }

    private boolean isWholeMinute(LocalTime time) {
        return time.getSecond() == 0 && time.getNano() == 0;
    }

    private BusinessException invalidWorkTime() {
        return new BusinessException(
                JobErrorCode.INVALID_WORK_TIME,
                "근무 시간은 분 단위로 1분 이상 24시간 이하여야 합니다. 자정을 넘기는 경우 endTimeNextDay를 true로 설정하세요."
        );
    }
}
