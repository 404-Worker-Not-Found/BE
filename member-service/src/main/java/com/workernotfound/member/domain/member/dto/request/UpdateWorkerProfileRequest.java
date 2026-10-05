package com.workernotfound.member.domain.member.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record UpdateWorkerProfileRequest(
    @PositiveOrZero Integer desiredHourlyWage,
    @Positive Integer activityRadiusKm,
    Boolean immediatelyAvailable,
    @Valid LocationRequest baseLocation,
    @Size(min = 1, max = 20) List<@NotBlank @Size(max = 100) String> preferredBusinessTypes,
    @Size(min = 1, max = 50) List<@NotNull @Valid WorkerAvailableTimeRequest> availableTimes
) {}
