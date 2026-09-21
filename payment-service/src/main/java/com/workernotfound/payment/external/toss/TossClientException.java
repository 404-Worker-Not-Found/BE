package com.workernotfound.payment.external.toss;

import com.workernotfound.payment.domain.order.exception.OrderErrorCode;
import com.workernotfound.payment.global.exception.BusinessException;

public class TossClientException extends BusinessException {
  private final Integer remoteStatus;

  public TossClientException(Integer remoteStatus, Throwable cause) {
    super(OrderErrorCode.PROVIDER_UNAVAILABLE);
    this.remoteStatus = remoteStatus;
    initCause(cause);
  }

  public Integer remoteStatus() {
    return remoteStatus;
  }
}
