package com.workernotfound.job.domain.job.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record MatchingSeatReservationRequest(
        @NotNull @Positive Long matchingId,
        @NotNull @Positive Long applicationId,
        @NotNull @Positive Long workerMemberId
) {
}
