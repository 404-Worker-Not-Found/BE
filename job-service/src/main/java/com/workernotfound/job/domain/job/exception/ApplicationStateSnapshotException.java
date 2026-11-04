package com.workernotfound.job.domain.job.exception;

/**
 * matching-service 공고별 지원 상태 스냅샷을 받지 못했다. 통신 실패, 실패 응답, 계약과 다른 응답을 모두 포함한다.
 *
 * <p>스냅샷 반영은 다음 실행에서 다시 시도하므로 호출자는 그 공고를 건너뛴다. 메시지에는 응답 원문을 넣지 않는다.
 */
public class ApplicationStateSnapshotException extends RuntimeException {

    public ApplicationStateSnapshotException(String message) {
        super(message);
    }

    public ApplicationStateSnapshotException(String message, Throwable cause) {
        super(message, cause);
    }
}
