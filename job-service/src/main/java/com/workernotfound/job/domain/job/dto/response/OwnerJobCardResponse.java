package com.workernotfound.job.domain.job.dto.response;

import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record OwnerJobCardResponse(

        Long id,

        String storeName,

        String title,

        JobStatus status,

        boolean isFundingBlocked,

        LocalDate workDate,

        LocalTime startTime,

        LocalTime endTime,

        boolean isEndTimeNextDay,

        LocalDateTime applicationDeadline,

        Integer recruitCount,

        long confirmedCount,

        // 지원자 수 projection의 현재 APPLIED 지원 수. 초기 적재 전인 공고는 상세 조회와 같이 null이다.
        Integer applicantCount

) {
    public static OwnerJobCardResponse of(JobPost post, long confirmedCount, Integer applicantCount) {
        return new OwnerJobCardResponse(
                post.getId(),
                post.getStoreName(),
                post.getTitle(),
                post.getStatus(),
                post.isFundingBlocked(),
                post.getWorkDate(),
                post.getStartTime(),
                post.getEndTime(),
                post.isEndTimeNextDay(),
                post.getApplicationDeadline(),
                post.getRecruitCount(),
                confirmedCount,
                applicantCount
        );
    }
}
