package com.workernotfound.member.domain.member.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateOwnerProfileRequest(
    @Size(max = 100) @Pattern(regexp = ".*\\S.*") String storeName,
    @Size(max = 100) @Pattern(regexp = ".*\\S.*") String businessType,
    @Valid LocationRequest storeLocation
) {}
