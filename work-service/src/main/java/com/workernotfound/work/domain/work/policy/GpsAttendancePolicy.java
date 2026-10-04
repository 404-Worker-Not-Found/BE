package com.workernotfound.work.domain.work.policy;

import com.workernotfound.work.domain.work.dto.request.CheckInRequest;
import com.workernotfound.work.domain.work.dto.response.AttendancePolicyResponse;
import com.workernotfound.work.domain.work.entity.Work;
import com.workernotfound.work.domain.work.exception.WorkErrorCode;
import com.workernotfound.work.global.exception.BusinessException;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class GpsAttendancePolicy implements AttendancePolicy {
  private static final double EARTH_RADIUS_METERS = 6371000;
  private final AttendanceProperties properties;

  @Override
  public CheckInDecision checkIn(Work work, CheckInRequest request, LocalDateTime now) {
    if (work.getLatitude() == null || work.getLongitude() == null)
      throw new BusinessException(WorkErrorCode.LOCATION_UNAVAILABLE);
    if (now.isBefore(work.scheduledStart().minus(properties.earlyWindow()))
        || now.isAfter(work.scheduledStart().plus(properties.lateWindow())))
      throw new BusinessException(WorkErrorCode.CHECK_IN_TIME_NOT_ALLOWED);
    double distance = distance(work, request);
    if (distance > properties.radiusMeters()) throw new BusinessException(WorkErrorCode.CHECK_IN_TOO_FAR);
    return new CheckInDecision(distance, "gps-radius-time-v1");
  }

  private double distance(Work work, CheckInRequest request) {
    double latitude = Math.toRadians(work.getLatitude().doubleValue());
    double otherLatitude = Math.toRadians(request.latitude().doubleValue());
    double deltaLatitude = otherLatitude - latitude;
    double deltaLongitude = Math.toRadians(request.longitude().subtract(work.getLongitude()).doubleValue());
    double a = Math.pow(Math.sin(deltaLatitude / 2), 2)
        + Math.cos(latitude) * Math.cos(otherLatitude) * Math.pow(Math.sin(deltaLongitude / 2), 2);
    return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(Math.max(0, Math.min(1, a))));
  }

  @Override
  public void validateStart(Work work, LocalDateTime now) {
    if (now.isBefore(work.scheduledStart())) throw new BusinessException(WorkErrorCode.START_TOO_EARLY);
  }

  @Override
  public void validateCompletion(Work work, LocalDateTime now) {
    if (!properties.allowEarlyCompletion() && now.isBefore(work.scheduledEnd()))
      throw new BusinessException(WorkErrorCode.COMPLETION_TOO_EARLY);
  }

  @Override
  public CompletionRole completionRole() { return properties.completionRole(); }

  @Override
  public AttendancePolicyResponse describe() {
    return new AttendancePolicyResponse(properties.radiusMeters(), properties.earlyWindow().getSeconds(),
        properties.lateWindow().getSeconds(), properties.timeZone().getId(), properties.allowEarlyCompletion(), completionRole().name());
  }
}
