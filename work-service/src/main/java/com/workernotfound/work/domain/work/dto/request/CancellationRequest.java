package com.workernotfound.work.domain.work.dto.request;

import jakarta.validation.constraints.*;

public record CancellationRequest(@NotBlank @Size(max = 500) String reason) {}
