package com.workernotfound.chat.domain.chat.controller.docs;

import com.workernotfound.chat.domain.chat.dto.request.ChatRoomRequest;
import com.workernotfound.chat.domain.chat.dto.response.ChatRoomResponse;
import com.workernotfound.chat.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "internalSecret")
@Tag(name = "채팅방 내부 API", description = "X-Internal-Secret 및 Idempotency-Key 필수")
public interface ChatRoomControllerDocs {
  @Operation(
      summary = "채팅방 생성",
      description = "동일 키와 요청은 기존 chatRoomId를 반환합니다. 보상 후 새 키로 재시도할 수 있습니다.")
  ApiResponse<ChatRoomResponse> create(
      @NotBlank @Size(max = 128) @Pattern(regexp = "[!-~]+") String key,
      @Valid ChatRoomRequest request);

  @Operation(summary = "채팅방 Saga 보상", description = "OPEN만 종료하며 종료된 채팅방의 재종료는 성공합니다.")
  ApiResponse<Void> close(
      @NotBlank @Size(max = 128) @Pattern(regexp = "[!-~]+") String key, @Positive Long chatRoomId);
}
