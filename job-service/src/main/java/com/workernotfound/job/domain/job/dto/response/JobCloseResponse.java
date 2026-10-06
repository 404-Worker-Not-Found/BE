package com.workernotfound.job.domain.job.dto.response;

import com.workernotfound.job.domain.job.entity.JobCloseRequest;
import com.workernotfound.job.domain.job.entity.enums.JobCloseResult;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import java.time.LocalDateTime;

/**
 * 점주 수동 마감 결과. 같은 {@code Idempotency-Key}의 재요청은 처음 결과를 그대로 받는다.
 *
 * @param result         {@code CLOSED}면 이 요청이 마감했고, {@code ALREADY_CLOSED}면 이미 마감된 공고라 바꾸지 않았다.
 * @param previousStatus 요청을 처리할 때의 공고 상태
 * @param status         처리 직후 공고 상태(항상 {@code CLOSED})
 * @param processedAt    요청을 처리한 시각
 */
public record JobCloseResponse(
        Long jobPostId,
        JobCloseResult result,
        JobStatus previousStatus,
        JobStatus status,
        LocalDateTime processedAt
) {

    public static JobCloseResponse of(JobCloseRequest request) {
        return new JobCloseResponse(
                request.getJobPostId(),
                request.getResult(),
                request.getPreviousStatus(),
                JobStatus.CLOSED,
                request.getProcessedAt()
        );
    }
}
