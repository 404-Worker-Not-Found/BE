package com.workernotfound.job.domain.job.dto.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record CreateJobRequest(

        Long businessId,

        Long categoryId,

        String storeName,

        String address,

        String title,

        String description,

        LocalDate workDate,

        LocalTime startTime,

        LocalTime endTime,

        Integer baseHourlyWage,

        Integer extraWage,

        Integer recruitCount,

        BigDecimal latitude,

        BigDecimal longitude,

        String urgencyLevel,

        LocalDateTime applicationDeadline

) {
}
