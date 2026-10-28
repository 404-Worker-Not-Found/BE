package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.enums.AdmissionConsumptionResult;

/**
 * 커밋된 {@code ApplicationSubmitted} 처리 결과다. 소비자는 이 값을 받은 뒤에만 ACK한다.
 *
 * <p>{@code isDuplicate}이면 이미 기록된 eventId이며, {@code result}는 처음 처리할 때 저장한 결과다. 승인은 다시 바뀌지 않았다.
 */
public record AdmissionEventOutcome(
        AdmissionConsumptionResult result,
        boolean isDuplicate
) {
}
