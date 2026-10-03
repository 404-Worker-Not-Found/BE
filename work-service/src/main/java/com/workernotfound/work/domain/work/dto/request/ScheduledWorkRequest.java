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
    @NotNull LocalTime endTime,
    @NotNull Boolean endTimeNextDay) {

  // 익일 여부를 보내지 않던 이전 요청도 같은 명령으로 처리되도록 역직렬화 시점에 시각으로 채운다.
  // 시각이 없으면 채우지 않고 Bean Validation이 400으로 거절한다.
  public ScheduledWorkRequest {
    if (endTimeNextDay == null && startTime != null && endTime != null) {
      endTimeNextDay = endsNextDay(startTime, endTime);
    }
  }

  // 명시적으로 전달한 익일 여부는 시각과 일치해야 한다.
  @AssertTrue(message = "endTimeNextDay는 종료 시각이 시작 시각 이하일 때만 true여야 합니다.")
  public boolean isEndTimeNextDayConsistent() {
    if (startTime == null || endTime == null || endTimeNextDay == null) return true;
    return endTimeNextDay == endsNextDay(startTime, endTime);
  }

  // 한 근무는 최대 24시간이므로 종료 시각이 시작 시각 이하이면 다음 날 종료다.
  private static boolean endsNextDay(LocalTime startTime, LocalTime endTime) {
    return !endTime.isAfter(startTime);
  }

  // 기존 명령 지문과 같도록 필드 추가 전 record 문자열 형식을 유지한다.
  // 익일 여부는 시각으로 결정되므로 지문에 따로 넣지 않아도 다른 요청이 같은 지문을 갖지 않는다.
  public String fingerprintPayload() {
    return "ScheduledWorkRequest[matchingId=" + matchingId
        + ", jobPostId=" + jobPostId
        + ", ownerMemberId=" + ownerMemberId
        + ", workerMemberId=" + workerMemberId
        + ", paymentId=" + paymentId
        + ", workDate=" + workDate
        + ", startTime=" + startTime
        + ", endTime=" + endTime
        + "]";
  }
}
