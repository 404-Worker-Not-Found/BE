package com.workernotfound.chat.domain.chat.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MatchConfirmedEvent(
    @NotBlank @Size(max = 128) @Pattern(regexp = "[!-~]+") String eventId,
    @NotBlank String eventType,
    @NotNull LocalDateTime occurredAt,
    @NotNull @Positive Long aggregateId,
    @NotNull @Positive Long revision,
    @NotNull Integer version,
    @NotNull @Positive Long matchingId,
    @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String chatRoomId,
    @NotBlank String workId,
    @NotNull @Positive Long jobPostId,
    @NotNull @Positive Long ownerMemberId,
    @NotNull @Positive Long workerMemberId) {}
