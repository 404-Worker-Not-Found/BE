package com.workernotfound.job.domain.job.entity.enums;

/**
 * 모집 완료 알림 전송 실패의 분류.
 *
 * <p>일시 오류는 기본 지연부터 backoff로 재시도한다. 인증·계약 오류는 운영자가 원인을 고쳐야 하므로 최대 지연으로 재시도하며
 * 로그 수준과 저장된 분류로 일시 오류와 구분한다. 어떤 분류도 명령을 성공으로 기록하거나 삭제하지 않는다.
 */
public enum RecruitmentCompletionFailureType {
    // matching-service에 결과가 확정되지 않은 매칭 확정 Saga가 남아 있다(APPLICATION-409-006 등).
    CONFLICT(true),
    TIMEOUT(true),
    NETWORK(true),
    THROTTLED(true),
    SERVER_ERROR(true),
    // 내부 secret 불일치 등 401·403 응답
    AUTHENTICATION(false),
    // 그 밖의 4xx 응답이나 계약과 다른 성공 응답
    CONTRACT(false);

    private final boolean isTransient;

    RecruitmentCompletionFailureType(boolean isTransient) {
        this.isTransient = isTransient;
    }

    public boolean isTransient() {
        return isTransient;
    }
}
