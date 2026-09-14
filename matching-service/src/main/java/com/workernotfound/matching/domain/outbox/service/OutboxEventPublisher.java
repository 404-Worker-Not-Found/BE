package com.workernotfound.matching.domain.outbox.service;

import com.workernotfound.matching.domain.outbox.model.OutboxMessage;

public interface OutboxEventPublisher {

	void publish(OutboxMessage message);
}
