package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.global.exception.BusinessException;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobWageCalculatorTests {

    private final JobWageCalculator calculator = new JobWageCalculator();

    @Test
    void calculatesDaytimeWage() {
        long wage = calculator.calculateWagePerWorker(
                LocalTime.of(9, 0), LocalTime.of(18, 0), false, 10_320, null);

        assertThat(wage).isEqualTo(92_880L);
    }

    @Test
    void addsExtraWagePerHour() {
        long wage = calculator.calculateWagePerWorker(
                LocalTime.of(9, 0), LocalTime.of(13, 0), false, 10_320, 2_000);

        assertThat(wage).isEqualTo(4 * 12_320L);
    }

    @Test
    void includesNextDayHours() {
        long wage = calculator.calculateWagePerWorker(
                LocalTime.of(22, 0), LocalTime.of(2, 0), true, 10_000, null);

        assertThat(wage).isEqualTo(40_000L);
    }

    @Test
    void allowsTwentyFourHourNextDayShift() {
        long wage = calculator.calculateWagePerWorker(
                LocalTime.of(9, 0), LocalTime.of(9, 0), true, 10_000, null);

        assertThat(wage).isEqualTo(240_000L);
    }

    @Test
    void floorsMinuteWageToWholeWon() {
        // 10,333원 × 31분 / 60 = 5,338.71...원 → 5,338원
        long wage = calculator.calculateWagePerWorker(
                LocalTime.of(9, 0), LocalTime.of(9, 31), false, 10_333, null);

        assertThat(wage).isEqualTo(5_338L);
    }

    @Test
    void floorsAfterAddingExtraWage() {
        // 시급을 먼저 합산하고 한 번만 내림한다: (10,333 + 7) × 31 / 60 = 5,342.33... → 5,342원
        // 기본·추가 금액을 따로 내림하면 5,338 + 3 = 5,341원이 된다.
        long wage = calculator.calculateWagePerWorker(
                LocalTime.of(9, 0), LocalTime.of(9, 31), false, 10_333, 7);

        assertThat(wage).isEqualTo(5_342L);
    }

    @Test
    void rejectsEndBeforeStartWithoutNextDay() {
        assertInvalidWorkTime(LocalTime.of(18, 0), LocalTime.of(9, 0), false);
        assertInvalidWorkTime(LocalTime.of(9, 0), LocalTime.of(9, 0), false);
    }

    @Test
    void rejectsNextDayShiftLongerThanOneDay() {
        assertInvalidWorkTime(LocalTime.of(9, 0), LocalTime.of(9, 1), true);
    }

    @Test
    void rejectsSubMinuteTimes() {
        assertInvalidWorkTime(LocalTime.of(9, 0, 30), LocalTime.of(18, 0), false);
        assertInvalidWorkTime(LocalTime.of(9, 0), LocalTime.of(18, 0, 0, 1), false);
    }

    @Test
    void rejectsInvalidWageInputs() {
        assertInvalidAmount(() -> calculator.calculateWagePerWorker(
                LocalTime.of(9, 0), LocalTime.of(10, 0), false, 0, null));
        assertInvalidAmount(() -> calculator.calculateWagePerWorker(
                LocalTime.of(9, 0), LocalTime.of(10, 0), false, 10_000, -1));
        assertInvalidAmount(() -> calculator.calculateWagePerWorker(
                LocalTime.of(9, 0), LocalTime.of(9, 1), false, 59, null));
    }

    @Test
    void totalIsPerWorkerTimesRecruitCount() {
        long perWorker = calculator.calculateWagePerWorker(
                LocalTime.of(9, 0), LocalTime.of(9, 31), false, 10_333, 7);

        long total = calculator.calculateTotalExpectedWage(perWorker, 3);

        assertThat(total).isEqualTo(perWorker * 3);
    }

    @Test
    void rejectsTotalOutOfRange() {
        assertInvalidAmount(() -> calculator.calculateTotalExpectedWage(Long.MAX_VALUE / 2, 3));
        assertInvalidAmount(() -> calculator.calculateTotalExpectedWage(
                calculator.calculateWagePerWorker(
                        LocalTime.of(0, 0), LocalTime.of(0, 0), true, Integer.MAX_VALUE, Integer.MAX_VALUE),
                Integer.MAX_VALUE));
        assertInvalidAmount(() -> calculator.calculateTotalExpectedWage(1_000L, 0));
    }

    @Test
    void acceptsTotalDepositOfExactlyOneHundredWon() {
        assertThat(calculator.calculateTotalExpectedWage(100L, 1)).isEqualTo(100L);
        assertThat(calculator.calculateTotalExpectedWage(50L, 2)).isEqualTo(100L);
    }

    @Test
    void rejectsTotalDepositBelowOneHundredWonWithoutRoundingUp() {
        // 1분 × 시급 5,999원 / 60 = 99.98원이므로 1인 금액은 99원으로 내림된다.
        long perWorker = calculator.calculateWagePerWorker(
                LocalTime.of(9, 0), LocalTime.of(9, 1), false, 5_999, null);
        assertThat(perWorker).isEqualTo(99L);

        assertThatThrownBy(() -> calculator.calculateTotalExpectedWage(perWorker, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("100원 이상")
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(JobErrorCode.INVALID_WAGE_AMOUNT);
        assertInvalidAmount(() -> calculator.calculateTotalExpectedWage(33L, 3));
        assertThat(calculator.calculateTotalExpectedWage(34L, 3)).isEqualTo(102L);
    }

    private void assertInvalidWorkTime(LocalTime start, LocalTime end, boolean isEndTimeNextDay) {
        assertThatThrownBy(() -> calculator.calculateWagePerWorker(start, end, isEndTimeNextDay, 10_000, null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(JobErrorCode.INVALID_WORK_TIME);
    }

    private void assertInvalidAmount(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(JobErrorCode.INVALID_WAGE_AMOUNT);
    }
}
