package com.workernotfound.job.domain.job.dto.response;

import com.workernotfound.job.domain.job.entity.JobPost;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;

public record JobDetailResponse(

        Long id,

        String storeName,
        String categoryName,
        String address,

        Integer baseHourlyWage,
        Integer extraWage,

        LocalDate workDate,
        LocalTime startTime,
        LocalTime endTime,
        int totalWorkMinutes,

        String description,

        int applicantCount,

        Integer recruitCount

) {
    public static JobDetailResponse of(JobPost post, String categoryName, int applicantCount) {
        int totalWorkMinutes = (int) Duration.between(post.getStartTime(), post.getEndTime()).toMinutes();
        return new JobDetailResponse(
                post.getId(),
                post.getStoreName(),
                categoryName,
                post.getAddress(),
                post.getBaseHourlyWage(),
                post.getExtraWage(),
                post.getWorkDate(),
                post.getStartTime(),
                post.getEndTime(),
                totalWorkMinutes,
                post.getDescription(),
                applicantCount,
                post.getRecruitCount()
        );
    }
}
