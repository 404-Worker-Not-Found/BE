package com.workernotfound.notification.domain.notification.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.*;
import java.time.*;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkNotificationEvent(
    @NotBlank @Size(max = 128) @Pattern(regexp = "[!-~]+") String eventId,
    @NotBlank String eventType, @NotNull @Positive Long aggregateId,
    @NotNull @Positive Long revision, @NotNull Integer version, @NotNull LocalDateTime occurredAt,
    @NotNull @Positive Long workId, @NotNull @Positive Long matchingId,
    @NotNull @Positive Long jobPostId, @NotNull @Positive Long ownerMemberId,
    @NotNull @Positive Long workerMemberId, @NotNull @Positive Long actorMemberId,
    @NotBlank String actorRole, @NotBlank String status,
    @NotNull LocalDate workDate, @NotNull LocalTime startTime, @NotNull LocalTime endTime,
    @NotNull Boolean endTimeNextDay) {}
