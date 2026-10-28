package com.workernotfound.work.domain.work.controller.docs;

import com.workernotfound.work.domain.work.dto.request.CancellationRequest;
import com.workernotfound.work.domain.work.dto.response.WorkCancellationResponse;
import com.workernotfound.work.global.response.ApiResponse;
import com.workernotfound.work.global.security.AuthenticatedMember;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

@Tag(name = "확정 근무 취소 동의")
@SecurityRequirement(name = "bearerAuth")
public interface WorkCancellationControllerDocs {
  @Operation(summary = "취소 동의 요청", description = "양측 동의 전에는 근무가 유지됩니다. 공고 시작 전·GPS 출근 전에만 요청합니다. 멱등 키는 근무·요청자 단위이며 사유 앞뒤 공백은 제거합니다.")
  ApiResponse<WorkCancellationResponse> request(@Parameter(hidden = true) AuthenticatedMember member,
      @Positive Long workId, @NotNull @Pattern(regexp = "[!-~]{1,128}") String key, @Valid CancellationRequest request);
  @Operation(summary = "취소 요청 내역", description = "근무 참여자에게 최근 20건을 반환합니다.")
  ApiResponse<List<WorkCancellationResponse>> getRequests(@Parameter(hidden = true) AuthenticatedMember member, @Positive Long workId);
  @Operation(summary = "상대방 취소 요청 동의", description = "요청자 본인은 동의할 수 없습니다. 동의 기한은 공고 시작 시각과 GPS 출근 중 빠른 때까지입니다. 동의만으로 PG 환불이 완료되지는 않습니다.")
  ApiResponse<WorkCancellationResponse> approve(@Parameter(hidden = true) AuthenticatedMember member, @Positive Long workId, @Positive Long requestId);
  @Operation(summary = "상대방 취소 요청 거절")
  ApiResponse<WorkCancellationResponse> reject(@Parameter(hidden = true) AuthenticatedMember member, @Positive Long workId, @Positive Long requestId);
  @Operation(summary = "내 취소 요청 철회", description = "상대방 동의·거절 전의 요청을 철회합니다. 근무는 유지됩니다.")
  ApiResponse<WorkCancellationResponse> withdraw(@Parameter(hidden = true) AuthenticatedMember member, @Positive Long workId, @Positive Long requestId);
}
