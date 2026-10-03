package com.workernotfound.chat.domain.chat.controller.docs;

import com.workernotfound.chat.domain.chat.dto.request.ChatMessageRequest;
import com.workernotfound.chat.domain.chat.dto.response.*;
import com.workernotfound.chat.global.response.ApiResponse;
import com.workernotfound.chat.global.security.AuthenticatedMember;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@Tag(name = "채팅 메시지", description = "확정된 OPEN 채팅방 참여자의 텍스트 대화")
@SecurityRequirement(name = "bearerAuth")
public interface ChatMessageControllerDocs {
  @Operation(summary = "메시지 전송", description = "clientMessageId는 영문·숫자·밑줄·하이픈 1~128자입니다. "
      + "같은 방·발신자·키·본문 재전송은 원래 메시지를 반환하고, 다른 본문은 409입니다. 본문은 최대 2000자입니다.")
  ApiResponse<ChatMessageResponse> send(
      @Parameter(hidden = true) AuthenticatedMember member,
      @Positive Long chatRoomId, @Valid ChatMessageRequest request);

  @Operation(summary = "메시지 내역 조회", description = "ID 내림차순. beforeId 미지정 시 최신 메시지부터 "
      + "조회하고, 다음 페이지는 nextBeforeId를 전달합니다. 타인·미확정·종료된 방은 404입니다.")
  ApiResponse<ChatMessagePageResponse> getMessages(
      @Parameter(hidden = true) AuthenticatedMember member,
      @Positive Long chatRoomId, @Positive Long beforeId, @Min(1) @Max(100) int size);
}
