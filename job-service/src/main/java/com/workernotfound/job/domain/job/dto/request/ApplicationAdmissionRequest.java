package com.workernotfound.job.domain.job.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ApplicationAdmissionRequest(
        @NotNull @Positive Long workerMemberId
) {
}
