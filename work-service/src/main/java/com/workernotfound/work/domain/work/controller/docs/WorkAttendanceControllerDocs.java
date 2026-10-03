package com.workernotfound.work.domain.work.controller.docs;

import com.workernotfound.work.domain.work.dto.request.CheckInRequest;
import com.workernotfound.work.domain.work.dto.response.*;
import com.workernotfound.work.global.response.ApiResponse;
import com.workernotfound.work.global.security.AuthenticatedMember;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

@Tag(name = "출근과 근무 확인")
@SecurityRequirement(name = "bearerAuth")
public interface WorkAttendanceControllerDocs {
  @Operation(summary = "현재 출근 인정 정책")
  ApiResponse<AttendancePolicyResponse> getPolicy();

  @Operation(summary = "알바생 GPS 출근", description = "확정된 본인 근무만 허용합니다. 반복 요청은 최초 출근 정보를 유지합니다.")
  ApiResponse<WorkResponse> checkIn(@Parameter(hidden = true) AuthenticatedMember member,
      @Positive Long workId, @Valid CheckInRequest request);

  @Operation(summary = "점주 근무 시작 확인", description = "출근한 근무를 예정 시작 시각 이후 시작합니다.")
  ApiResponse<WorkResponse> start(@Parameter(hidden = true) AuthenticatedMember member, @Positive Long workId);

  @Operation(summary = "근무 완료 확인", description = "진행 중인 근무만 완료합니다. 기본 정책은 점주가 예정 종료 이후 확인하며, 주체와 시간 정책은 설정으로 변경할 수 있습니다.")
  ApiResponse<WorkResponse> complete(@Parameter(hidden = true) AuthenticatedMember member, @Positive Long workId);
}
