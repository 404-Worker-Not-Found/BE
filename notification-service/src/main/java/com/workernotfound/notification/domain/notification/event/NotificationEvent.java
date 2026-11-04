package com.workernotfound.notification.domain.notification.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

// matching-service 이벤트 중 알림을 만드는 MatchConfirmed·ApplicationRejected·MatchingProposed·MatchingCandidatesExhausted.
// 지원·알바생 값은 후보 소진(공고 단위)에는 없으므로 종류별로 검증한다.
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationEvent(
    @NotBlank @Size(max = 128) @Pattern(regexp = "[!-~]+") String eventId,
    @NotBlank String eventType,
    @NotNull LocalDateTime occurredAt,
    @NotNull @Positive Long aggregateId,
    @NotNull @Positive Long revision,
    @NotNull Integer version,
    @Positive Long matchingId,
    @Positive Long applicationId,
    @NotNull @Positive Long jobPostId,
    @Positive Long ownerMemberId,
    @Positive Long workerMemberId,
    String status,
    @Positive Integer remainingSeats) {}
