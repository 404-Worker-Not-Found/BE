package com.workernotfound.notification.domain.push.service;

import java.time.Instant;

public record PushDispatch(long id, String leaseToken, int attempt, long notificationId,
    long deviceTokenId, long generation, String token, String type, Instant expiresAt) {
  @Override public String toString() { return "PushDispatch[id=" + id + ",attempt=" + attempt + "]"; }
}
