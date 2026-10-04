package com.workernotfound.job.domain.job.dto.response;

import com.workernotfound.job.domain.job.entity.JobMatchingSeatReservation;
import com.workernotfound.job.domain.job.entity.enums.MatchingSeatReservationStatus;

public record MatchingSeatReservationCommandResponse(
        String reservationId,
        MatchingSeatReservationStatus status
) {
    public static MatchingSeatReservationCommandResponse of(JobMatchingSeatReservation reservation) {
        return new MatchingSeatReservationCommandResponse(
                String.valueOf(reservation.getId()),
                reservation.getStatus()
        );
    }
}
