package com.workernotfound.member.domain.member.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UpdateMyMemberRequest(
    @Size(max = 50) @Pattern(regexp = ".*\\S.*") String name,
    @Valid UpdateWorkerProfileRequest workerProfile,
    @Valid UpdateOwnerProfileRequest ownerProfile
) {
public record UpdateOwnerProfileRequest(
    @Size(max = 100) @Pattern(regexp = ".*\\S.*") String storeName,
    @Size(max = 100) @Pattern(regexp = ".*\\S.*") String businessType,
    @Valid LocationRequest storeLocation
) {}
public record UpdateWorkerProfileRequest(
    @PositiveOrZero Integer desiredHourlyWage,
    @Positive Integer activityRadiusKm,
    Boolean immediatelyAvailable,
    @Valid LocationRequest baseLocation,
    @Size(min = 1, max = 20) List<@NotBlank @Size(max = 100) String> preferredBusinessTypes,
    @Size(min = 1, max = 50) List<@NotNull @Valid WorkerAvailableTimeRequest> availableTimes
) {}
}
