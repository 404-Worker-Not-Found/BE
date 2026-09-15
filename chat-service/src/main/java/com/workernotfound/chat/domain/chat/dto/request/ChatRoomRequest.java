package com.workernotfound.chat.domain.chat.dto.request;

import jakarta.validation.constraints.*;

public record ChatRoomRequest(
    @NotNull @Positive Long matchingId,
    @NotNull @Positive Long jobPostId,
    @NotNull @Positive Long ownerMemberId,
    @NotNull @Positive Long workerMemberId,
    @NotBlank @Size(max = 255) String workId) {}
