package com.workernotfound.payment.external.toss;

import com.workernotfound.payment.domain.order.exception.OrderErrorCode;
import com.workernotfound.payment.global.exception.BusinessException;

/**
 * 토스 호출 실패. remoteStatus·remoteCode는 응답을 받았을 때만 있다. 응답 본문은 보관하지 않고 대문자 오류 코드만 남긴다.
 */
public class TossClientException extends BusinessException {
  private final Integer remoteStatus;
  private final String remoteCode;

  public TossClientException(Integer remoteStatus, Throwable cause) {
    this(remoteStatus, null, cause);
  }

  public TossClientException(Integer remoteStatus, String remoteCode, Throwable cause) {
    super(OrderErrorCode.PROVIDER_UNAVAILABLE);
    this.remoteStatus = remoteStatus;
    this.remoteCode = remoteCode;
    initCause(cause);
  }

  public Integer remoteStatus() {
    return remoteStatus;
  }

  public String remoteCode() {
    return remoteCode;
  }
}
