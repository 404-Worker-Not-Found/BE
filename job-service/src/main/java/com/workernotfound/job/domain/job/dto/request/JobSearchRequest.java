package com.workernotfound.job.domain.job.dto.request;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

public record JobSearchRequest(

        @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0")
        BigDecimal workerLat,

        @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0")
        BigDecimal workerLng,

        @PositiveOrZero
        Double maxDistanceKm,

        @Min(10320)
        Integer minWage,

        @Min(10320)
        Integer maxWage,

        LocalTime startTimeFrom,

        LocalTime startTimeTo,

        List<Long> categoryIds,

        String type,

        @PositiveOrZero
        Integer page,

        @Positive @Max(100)
        Integer size

) {
}
