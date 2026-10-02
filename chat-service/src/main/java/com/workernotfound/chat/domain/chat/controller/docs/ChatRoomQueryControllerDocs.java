package com.workernotfound.chat.domain.chat.controller.docs;

import com.workernotfound.chat.domain.chat.dto.response.*;
import com.workernotfound.chat.global.response.ApiResponse;
import com.workernotfound.chat.global.security.AuthenticatedMember;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;

@Tag(name = "내 채팅방", description = "매칭 확정이 반영된 참여자 채팅방 조회")
@SecurityRequirement(name = "bearerAuth")
public interface ChatRoomQueryControllerDocs {
  @Operation(summary = "내 채팅방 목록", description = "생성 시각·ID 내림차순. page는 0부터, size는 1~100입니다.")
  ApiResponse<ChatRoomPageResponse> getChatRooms(
      @Parameter(hidden = true) AuthenticatedMember member,
      @Min(0) int page,
      @Min(1) @Max(100) int size);

  @Operation(summary = "내 채팅방 상세", description = "다른 회원, 미확정 또는 종료된 채팅방은 404를 반환합니다.")
  ApiResponse<ChatRoomDetailResponse> getChatRoom(
      @Parameter(hidden = true) AuthenticatedMember member, @Positive Long chatRoomId);
}
