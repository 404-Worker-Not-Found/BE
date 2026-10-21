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
  @Operation(summary = "내 채팅방 목록", description = "생성 시각·ID 내림차순. page는 0부터, size는 1~100입니다. "
      + "myReadPosition·otherReadPosition은 각 참가자의 마지막 읽은 메시지 ID와 서버 갱신 시각이며, "
      + "기록이 없으면 null입니다. unreadCount는 내 읽음 위치 이후 상대가 보낸 메시지 수입니다. "
      + "조회는 읽음 처리하지 않습니다.")
  ApiResponse<ChatRoomPageResponse> getChatRooms(
      @Parameter(hidden = true) AuthenticatedMember member,
      @Min(0) int page,
      @Min(1) @Max(100) int size);

  @Operation(summary = "내 채팅방 상세", description = "다른 회원, 역할 불일치, 미확정 또는 종료된 채팅방은 404입니다. "
      + "myReadPosition·otherReadPosition은 마지막 읽은 메시지 ID와 서버 갱신 시각이며 기록이 없으면 null입니다. "
      + "unreadCount는 내 읽음 위치 이후 상대가 보낸 메시지 수이며, 내 기록이 없으면 상대 메시지 전체를 셉니다. "
      + "재접속 시 이 API로 다시 확인할 수 있고 조회만으로 읽음 처리되지 않습니다.")
  ApiResponse<ChatRoomDetailResponse> getChatRoom(
      @Parameter(hidden = true) AuthenticatedMember member, @Positive Long chatRoomId);
}
