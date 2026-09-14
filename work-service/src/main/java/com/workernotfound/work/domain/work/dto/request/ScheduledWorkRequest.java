package com.workernotfound.work.domain.work.dto.request;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.LocalTime;

public record ScheduledWorkRequest(
    @NotNull @Positive Long matchingId,
    @NotNull @Positive Long jobPostId,
    @NotNull @Positive Long ownerMemberId,
    @NotNull @Positive Long workerMemberId,
    @NotBlank @Size(max = 255) String paymentId,
    @NotNull LocalDate workDate,
    @NotNull LocalTime startTime,
    @NotNull LocalTime endTime) {}
