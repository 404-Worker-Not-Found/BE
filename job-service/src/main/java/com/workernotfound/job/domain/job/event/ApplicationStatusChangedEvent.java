package com.workernotfound.job.domain.job.event;

import com.workernotfound.job.domain.job.entity.enums.ApplicationStatus;

/**
 * matching-service 지원 상태 이벤트({@code ApplicationSubmitted}·{@code ApplicationCanceled}·{@code ApplicationSelected}·
 * {@code ApplicationRejected} v1) 중 지원자 수 projection이 쓰는 값.
 *
 * <p>{@code revision}은 matching-service {@code applications.revision}이며 순서 판단 기준이다. {@code eventId}는 재전달 판별과
 * 운영 확인에만 쓴다.
 */
public record ApplicationStatusChangedEvent(
        String eventId,
        Long applicationId,
        Long jobPostId,
        ApplicationStatus status,
        Long revision
) {
}
