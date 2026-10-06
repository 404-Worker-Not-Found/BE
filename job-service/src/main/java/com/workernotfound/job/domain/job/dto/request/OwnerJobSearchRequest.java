package com.workernotfound.job.domain.job.dto.request;

import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

// 점주 본인 공고 목록 조건. 점주 ID는 요청 값이 아니라 JWT의 memberId로 정한다.
public record OwnerJobSearchRequest(

        JobStatus status,

        @PositiveOrZero
        Integer page,

        @Positive @Max(100)
        Integer size

) {
}
