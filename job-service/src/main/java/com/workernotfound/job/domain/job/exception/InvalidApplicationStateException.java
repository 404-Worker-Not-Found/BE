package com.workernotfound.job.domain.job.exception;

/**
 * 해석할 수 없거나 계약을 어긴 matching-service 지원 상태 입력(지원 상태 이벤트·공고별 지원 상태 스냅샷).
 *
 * <p>envelope·payload 불일치, 지원하지 않는 스키마 버전, 같은 revision의 다른 상태, 저장된 지원과 다른 공고 ID처럼 재시도로
 * 해결되지 않는 경우다. 반영 트랜잭션을 롤백하며, 이벤트는 ACK하지 않아 pending으로 남기고 스냅샷은 그 공고의 적재를 실패로 둔다.
 */
public class InvalidApplicationStateException extends RuntimeException {

    public InvalidApplicationStateException(String message) {
        super(message);
    }

    public InvalidApplicationStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
