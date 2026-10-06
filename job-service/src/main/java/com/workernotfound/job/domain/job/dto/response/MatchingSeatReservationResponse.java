package com.workernotfound.job.domain.job.dto.response;

import com.workernotfound.job.domain.job.entity.JobMatchingSeatReservation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record MatchingSeatReservationResponse(
        // matching-service 계약에 맞춰 외부 식별자를 문자열로 직렬화한다.
        String reservationId,
        Long jobPostId,
        Long jobVersion,
        Long ownerMemberId,
        LocalDate workDate,
        LocalTime startTime,
        LocalTime endTime,
        Boolean endTimeNextDay,
        BigDecimal lockedAmount,
        String currency,
        LocalDateTime reservedAt,
        LocalDateTime expiresAt,
        BigDecimal latitude,
        BigDecimal longitude
) {
    // 공고를 다시 읽지 않는다. 예약에 저장된 발급 당시 스냅샷만 사용해야 재요청 응답이 같다.
    public static MatchingSeatReservationResponse of(JobMatchingSeatReservation reservation) {
        return new MatchingSeatReservationResponse(
                String.valueOf(reservation.getId()),
                reservation.getJobPostId(),
                reservation.getJobVersion(),
                reservation.getOwnerMemberId(),
                reservation.getWorkDate(),
                reservation.getStartTime(),
                reservation.getEndTime(),
                reservation.isEndTimeNextDay(),
                BigDecimal.valueOf(reservation.getLockedAmount()),
                reservation.getCurrency(),
                reservation.getReservedAt(),
                reservation.getExpiresAt(),
                reservation.getLatitude(),
                reservation.getLongitude()
        );
    }
}
