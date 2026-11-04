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
        int workHours,
        int workMinutes,

        String description,

        Integer applicantCount,

        Integer recruitCount

) {
    // applicantCount: 지원자 수 projection의 현재 APPLIED 지원 수. 초기 적재 전인 공고는 null이다.
    public static JobDetailResponse of(JobPost post, String categoryName, Integer applicantCount) {
        int rawMinutes = (int) Duration.between(post.getStartTime(), post.getEndTime()).toMinutes();
        int totalMinutes = post.isEndTimeNextDay() ? rawMinutes + 1440 : rawMinutes;
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
                totalMinutes / 60,
                totalMinutes % 60,
                post.getDescription(),
                applicantCount,
                post.getRecruitCount()
        );
    }
}
