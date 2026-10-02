package com.workernotfound.work.domain.work.controller.docs;

import com.workernotfound.work.domain.work.dto.response.*;
import com.workernotfound.work.global.response.ApiResponse;
import com.workernotfound.work.global.security.AuthenticatedMember;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;

@Tag(name = "내 근무", description = "매칭 확정이 반영된 본인 근무 조회")
@SecurityRequirement(name = "bearerAuth")
public interface WorkQueryControllerDocs {
  @Operation(summary = "내 근무 목록", description = "날짜·시작 시각·ID 내림차순. page는 0부터, size는 1~100입니다.")
  ApiResponse<WorkPageResponse> getWorks(
      @Parameter(hidden = true) AuthenticatedMember member,
      @Min(0) int page,
      @Min(1) @Max(100) int size);

  @Operation(summary = "내 근무 상세", description = "다른 회원의 근무와 미확정 근무는 404를 반환합니다.")
  ApiResponse<WorkResponse> getWork(
      @Parameter(hidden = true) AuthenticatedMember member, @Positive Long workId);
}
