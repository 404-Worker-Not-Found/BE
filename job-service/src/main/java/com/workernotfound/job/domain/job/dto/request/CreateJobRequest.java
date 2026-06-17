package com.workernotfound.job.domain.job.dto.request;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record CreateJobRequest(

        @NotNull
        Long businessId,

        @NotNull
        Long categoryId,

        @NotBlank
        String storeName,

        @NotBlank
        String address,

        @NotBlank
        String title,

        @NotBlank
        String description,

        @NotNull
        @FutureOrPresent
        LocalDate workDate,

        @NotNull
        LocalTime startTime,

        @NotNull
        LocalTime endTime,

        boolean isEndTimeNextDay,

        @NotNull
        @Min(10320)
        Integer baseHourlyWage,

        @Positive
        Integer extraWage,

        @NotNull
        @Positive
        Integer recruitCount,

        @NotNull
        @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0")
        BigDecimal latitude,

        @NotNull
        @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0")
        BigDecimal longitude,

        @NotBlank
        String urgencyLevel,

        @NotNull
        @Future
        LocalDateTime applicationDeadline

) {
}
