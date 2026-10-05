package com.workernotfound.member.domain.member.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateMyMemberRequest(
    @Size(max = 50) @Pattern(regexp = ".*\\S.*") String name,
    @Valid UpdateWorkerProfileRequest workerProfile,
    @Valid UpdateOwnerProfileRequest ownerProfile
) {}
