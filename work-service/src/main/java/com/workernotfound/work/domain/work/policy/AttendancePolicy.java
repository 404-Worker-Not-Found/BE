package com.workernotfound.work.domain.work.policy;

import com.workernotfound.work.domain.work.dto.request.CheckInRequest;
import com.workernotfound.work.domain.work.dto.response.AttendancePolicyResponse;
import com.workernotfound.work.domain.work.entity.Work;
import java.time.LocalDateTime;

public interface AttendancePolicy {
  CheckInDecision checkIn(Work work, CheckInRequest request, LocalDateTime now);
  void validateStart(Work work, LocalDateTime now);
  void validateCompletion(Work work, LocalDateTime now);
  CompletionRole completionRole();
  AttendancePolicyResponse describe();
}
