package com.workernotfound.job.domain.job.event;

import com.workernotfound.job.domain.job.service.AdmissionConsumptionCommand;
import java.time.LocalDateTime;

/**
 * matching-service {@code ApplicationSubmitted} v1에서 승인 사용 완료 처리와 수신 기록에 쓰는 값이다.
 * envelope와 payload의 일치 검증을 통과한 뒤에만 만든다.
 *
 * <p>{@code occurredAt}은 envelope의 발생 시각이다. {@code admissionId}, {@code jobPostId}, {@code workerMemberId},
 * {@code occurredAt}은 이벤트 원문대로 비어 있을 수 있으며 처리 결과의 영구 거절로 분류된다.
 */
public record ApplicationSubmittedEvent(
        String eventId,
        Long applicationId,
        Long revision,
        Long admissionId,
        Long jobPostId,
        Long workerMemberId,
        LocalDateTime occurredAt
) {

    public AdmissionConsumptionCommand toConsumptionCommand() {
        return new AdmissionConsumptionCommand(admissionId, jobPostId, workerMemberId, occurredAt);
    }
}
