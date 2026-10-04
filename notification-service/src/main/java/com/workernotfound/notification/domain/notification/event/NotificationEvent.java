package com.workernotfound.notification.domain.notification.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationEvent(
    @NotBlank @Size(max = 128) @Pattern(regexp = "[!-~]+") String eventId,
    @NotBlank String eventType,
    @NotNull LocalDateTime occurredAt,
    @NotNull @Positive Long aggregateId,
    @NotNull @Positive Long revision,
    @NotNull Integer version,
    Long matchingId,
    @NotNull @Positive Long applicationId,
    @NotNull @Positive Long jobPostId,
    Long ownerMemberId,
    @NotNull @Positive Long workerMemberId,
    String status) {}
