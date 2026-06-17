package com.workernotfound.job.domain.job.dto.response;

import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.UrgencyLevel;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

public record JobCardResponse(

        Long id,

        String storeName,

        String categoryName,

        LocalDate workDate,

        LocalTime startTime,

        LocalTime endTime,

        Integer baseHourlyWage,

        Integer extraWage,

        Long remainingMinutes,

        Double distanceKm,

        UrgencyLevel urgencyLevel,

        boolean isUrgent,

        String status

) {
    public static JobCardResponse of(JobPost post, String categoryName, Double distanceKm) {
        long remainingMinutes = ChronoUnit.MINUTES.between(
                LocalDateTime.now(), post.getApplicationDeadline()
        );
        return new JobCardResponse(
                post.getId(),
                post.getStoreName(),
                categoryName,
                post.getWorkDate(),
                post.getStartTime(),
                post.getEndTime(),
                post.getBaseHourlyWage(),
                post.getExtraWage(),
                remainingMinutes,
                distanceKm,
                post.getUrgencyLevel(),
                post.getUrgencyLevel() == UrgencyLevel.HIGH,
                post.getStatus().name()
        );
    }
}
