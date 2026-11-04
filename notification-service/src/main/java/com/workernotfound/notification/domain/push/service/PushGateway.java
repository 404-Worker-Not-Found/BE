package com.workernotfound.notification.domain.push.service;

public interface PushGateway {
  PushResult send(PushDispatch dispatch);
}
