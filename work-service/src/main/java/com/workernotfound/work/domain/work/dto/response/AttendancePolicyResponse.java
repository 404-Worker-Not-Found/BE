package com.workernotfound.work.domain.work.dto.response;

public record AttendancePolicyResponse(double radiusMeters, long earlyWindowSeconds,
    long lateWindowSeconds, String timeZone, boolean allowEarlyCompletion, String completionRole) {}
