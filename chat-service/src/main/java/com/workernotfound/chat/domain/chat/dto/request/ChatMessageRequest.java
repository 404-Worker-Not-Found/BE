package com.workernotfound.chat.domain.chat.dto.request;

import jakarta.validation.constraints.*;

public record ChatMessageRequest(
    @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,128}") String clientMessageId,
    @NotBlank @Size(max = 2000) String content) {}
