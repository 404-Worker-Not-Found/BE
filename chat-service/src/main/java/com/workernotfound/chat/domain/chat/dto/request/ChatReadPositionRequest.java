package com.workernotfound.chat.domain.chat.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ChatReadPositionRequest(@NotNull @Positive Long lastReadMessageId) {}
