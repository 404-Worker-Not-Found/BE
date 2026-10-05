package com.workernotfound.job.domain.job.exception;

import com.workernotfound.job.domain.job.entity.enums.PaymentOrderFailureType;
import lombok.Getter;

/**
 * 결제 주문 생성 요청이 성공으로 확인되지 않은 결과.
 *
 * <p>상대 응답 원문이나 요청 헤더는 담지 않는다. HTTP 상태와 형식이 맞는 응답 오류 코드만 보존해 재시도 판단과 관찰에 사용한다.
 */
@Getter
public class PaymentOrderCreationException extends RuntimeException {

    private final PaymentOrderFailureType failureType;
    private final Integer httpStatus;
    private final String responseCode;

    public PaymentOrderCreationException(PaymentOrderFailureType failureType, Integer httpStatus, String responseCode) {
        super(message(failureType, httpStatus, responseCode));
        this.failureType = failureType;
        this.httpStatus = httpStatus;
        this.responseCode = responseCode;
    }

    // 전송 실패. 응답 헤더를 이미 받았다면 그 HTTP 상태를 진단 정보로 함께 보존한다.
    public PaymentOrderCreationException(PaymentOrderFailureType failureType, Integer receivedHttpStatus, Throwable cause) {
        super(message(failureType, receivedHttpStatus, null), cause);
        this.failureType = failureType;
        this.httpStatus = receivedHttpStatus;
        this.responseCode = null;
    }

    private static String message(PaymentOrderFailureType failureType, Integer httpStatus, String responseCode) {
        return "결제 주문 생성 실패: type=%s, status=%s, code=%s".formatted(failureType, httpStatus, responseCode);
    }
}
