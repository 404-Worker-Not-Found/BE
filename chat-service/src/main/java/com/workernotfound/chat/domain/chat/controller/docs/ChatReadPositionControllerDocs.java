package com.workernotfound.chat.domain.chat.controller.docs;

import com.workernotfound.chat.domain.chat.dto.request.ChatReadPositionRequest;
import com.workernotfound.chat.domain.chat.dto.response.ChatReadPositionResponse;
import com.workernotfound.chat.global.response.ApiResponse;
import com.workernotfound.chat.global.security.AuthenticatedMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

@Tag(name = "채팅 읽음 위치", description = "확정된 OPEN 채팅방 참가자의 명시적 읽음 처리")
@SecurityRequirement(name = "bearerAuth")
public interface ChatReadPositionControllerDocs {
  @Operation(summary = "내 읽음 위치 갱신", description = "실제로 확인한 마지막 메시지 ID를 전송합니다. "
      + "해당 방의 그 메시지까지 읽은 것으로 처리하며 본인 메시지도 지정할 수 있습니다. "
      + "같거나 더 작은 ID는 위치와 readAt을 변경하지 않고 현재 값을 반환합니다. "
      + "readAt은 서버에서 읽음 위치를 마지막으로 전진시킨 시각입니다. "
      + "타인·역할 불일치·미확정·종료된 방은 CHAT-404-001, "
      + "없는 메시지·다른 방 메시지는 CHAT-404-002입니다.")
  ApiResponse<ChatReadPositionResponse> advanceReadPosition(
      @Parameter(hidden = true) AuthenticatedMember member,
      @Positive Long chatRoomId, @Valid ChatReadPositionRequest request);
}
