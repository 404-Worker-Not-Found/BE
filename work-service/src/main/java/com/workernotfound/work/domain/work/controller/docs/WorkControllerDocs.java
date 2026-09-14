package com.workernotfound.work.domain.work.controller.docs;

import com.workernotfound.work.domain.work.dto.request.ScheduledWorkRequest;
import com.workernotfound.work.domain.work.dto.response.ScheduledWorkResponse;
import com.workernotfound.work.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "internalSecret")
@Tag(name = "예정 근무 내부 API", description = "X-Internal-Secret 및 Idempotency-Key 필수")
public interface WorkControllerDocs {
  @Operation(
      summary = "예정 근무 생성",
      description = "동일 키와 요청은 기존 workId를 반환합니다. 보상 후 새 키로 재시도할 수 있습니다.")
  ApiResponse<ScheduledWorkResponse> create(
      @NotBlank @Size(max = 128) @Pattern(regexp = "[!-~]+") String key,
      @Valid ScheduledWorkRequest request);

  @Operation(summary = "예정 근무 Saga 보상", description = "SCHEDULED만 취소하며 취소된 근무의 재취소는 성공합니다.")
  ApiResponse<Void> cancel(
      @NotBlank @Size(max = 128) @Pattern(regexp = "[!-~]+") String key, @Positive Long workId);
}
