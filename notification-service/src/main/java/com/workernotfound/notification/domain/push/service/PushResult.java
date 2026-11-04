package com.workernotfound.notification.domain.push.service;

import java.time.Duration;

public record PushResult(Outcome outcome, String code, Duration retryAfter) {
  public enum Outcome { ACCEPTED, RETRY, INVALID_TOKEN, REJECTED }
}
