package com.workernotfound.job.domain.job.dto.request;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

public record JobSearchRequest(

        BigDecimal workerLat,

        BigDecimal workerLng,

        Double maxDistanceKm,

        Integer minWage,

        Integer maxWage,

        LocalTime startTimeFrom,

        LocalTime startTimeTo,

        List<Long> categoryIds,

        String type

) {
}
