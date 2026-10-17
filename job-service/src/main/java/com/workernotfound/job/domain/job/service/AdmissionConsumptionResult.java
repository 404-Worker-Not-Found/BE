package com.workernotfound.job.domain.job.service;

/**
 * 승인 사용 완료 처리의 확정된 결과다. 같은 이벤트를 다시 처리해도 결과가 바뀌지 않으므로 소비자는 결과를 기록하고 처리를 끝낼 수 있다.
 *
 * <p>DB 장애 같은 일시 오류는 결과로 바꾸지 않고 예외로 전파한다. 소비자는 예외가 난 이벤트를 재시도한다.
 */
public enum AdmissionConsumptionResult {

    /** 만료 시각 전에 저장된 지원으로 RESERVED 또는 EXPIRED 승인을 CONSUMED로 바꿨다. */
    CONSUMED(false),
    /** 이미 CONSUMED인 승인이다. 상태와 consumedAt을 바꾸지 않았다. */
    ALREADY_CONSUMED(false),
    /** 지원 저장 시각이 승인 만료 시각과 같거나 늦다. 상태를 바꾸지 않았다. */
    SUBMITTED_AFTER_EXPIRY(false),
    /** 이벤트에 admissionId가 없다. */
    REJECTED_MISSING_ADMISSION_ID(true),
    /** 이벤트에 발생 시각이 없어 만료 전 지원인지 판단할 수 없다. */
    REJECTED_MISSING_OCCURRED_AT(true),
    /** admissionId에 해당하는 승인이 없다. */
    REJECTED_ADMISSION_NOT_FOUND(true),
    /** 이벤트의 공고 ID가 승인의 공고 ID와 다르거나 비어 있다. */
    REJECTED_JOB_POST_MISMATCH(true),
    /** 이벤트의 알바생 회원 ID가 승인의 회원 ID와 다르거나 비어 있다. */
    REJECTED_WORKER_MISMATCH(true);

    private final boolean rejected;

    AdmissionConsumptionResult(boolean rejected) {
        this.rejected = rejected;
    }

    // 재시도로 해결되지 않는 계약 위반 이벤트다. 정상 결과와 구분해 운영자가 확인할 수 있게 기록한다.
    public boolean isRejected() {
        return rejected;
    }
}
