package com.workernotfound.job.domain.job.entity.enums;

/**
 * 결제 주문 생성 요청이 주문 생성 성공으로 확인되지 않은 이유.
 *
 * <p>일시 오류는 같은 키·같은 요청으로 backoff 재시도한다. 나머지는 운영자가 원인을 확인해야 하므로 최대 지연으로만 다시 확인하고
 * 오류 로그로 알린다. 어떤 분류도 명령을 성공으로 기록하거나 공고에 주문을 연결하지 않는다.
 */
public enum PaymentOrderFailureType {
    // 연결·응답 헤더·본문 수신을 포함한 전체 호출 제한시간 초과, 408. 상대가 주문을 만들었을 수 있다.
    TIMEOUT(true),
    // 연결 실패, 본문 수신 중 연결 끊김 등 전송 오류. 상대가 주문을 만들었을 수 있다.
    NETWORK(true),
    THROTTLED(true),
    SERVER_ERROR(true),
    // 내부 secret 불일치 등 401·403 응답
    AUTHENTICATION(false),
    // 409. 같은 키의 다른 요청 내용, 또는 같은 공고의 다른 활성 주문과 충돌
    CONFLICT(false),
    // 그 밖의 4xx, 끝까지 받았지만 ApiResponse 계약이나 필수 필드가 맞지 않는 2xx
    CONTRACT(false),
    // 응답의 공고 ID·버전·금액·통화가 저장한 요청 스냅샷과 다름
    SNAPSHOT_MISMATCH(false),
    // 응답 주문이 이미 다른 주문으로 대체됨(SUPERSEDED). 공고에 연결할 최신 주문이 아니다.
    ORDER_SUPERSEDED(false);

    private final boolean isTransient;

    PaymentOrderFailureType(boolean isTransient) {
        this.isTransient = isTransient;
    }

    public boolean isTransient() {
        return isTransient;
    }
}
