package com.workernotfound.job.domain.job.dto.request;

import java.time.LocalDate;
import java.time.LocalTime;

public record CreateJobRequest(

        Long businessId,

        Long categoryId,

        String title,

        String description,

        LocalDate workDate,

        LocalTime startTime,

        LocalTime endTime,

        Integer baseHourlyWage,

        Integer recruitCount

) {
}
