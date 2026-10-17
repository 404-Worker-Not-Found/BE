package com.workernotfound.job.domain.job.service;

import java.time.LocalDateTime;

/**
 * matching-service {@code ApplicationSubmitted} 이벤트에서 승인 사용 완료 처리에 필요한 값만 옮긴 입력이다.
 *
 * <p>{@code occurredAt}은 이벤트 envelope의 발생 시각(지원 저장 시각)이며, job-service가 이벤트를 받은 시각이 아니다.
 * 필드는 이벤트 원문을 그대로 옮기며 누락 값은 처리 결과의 거절로 분류한다.
 */
public record AdmissionConsumptionCommand(
        Long admissionId,
        Long jobPostId,
        Long workerMemberId,
        LocalDateTime occurredAt
) {
}
