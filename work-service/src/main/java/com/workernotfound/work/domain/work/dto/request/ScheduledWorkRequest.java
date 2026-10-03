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

  // 익일 종료는 종료 시각이 시작 시각 이하일 때만 성립한다. 시각과 어긋난 일정은 저장하지 않는다.
  @AssertTrue(message = "endTimeNextDay는 종료 시각이 시작 시각 이하일 때만 true여야 합니다.")
  public boolean isEndTimeNextDayConsistent() {
    if (startTime == null || endTime == null || endTimeNextDay == null) return true;
    return endTimeNextDay == !endTime.isAfter(startTime);
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
