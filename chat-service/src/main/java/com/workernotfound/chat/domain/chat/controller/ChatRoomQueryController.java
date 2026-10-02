package com.workernotfound.chat.domain.chat.controller;

import com.workernotfound.chat.domain.chat.controller.docs.ChatRoomQueryControllerDocs;
import com.workernotfound.chat.domain.chat.dto.response.*;
import com.workernotfound.chat.domain.chat.service.ChatRoomQueryService;
import com.workernotfound.chat.global.response.ApiResponse;
import com.workernotfound.chat.global.security.AuthenticatedMember;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat-rooms/me")
public class ChatRoomQueryController implements ChatRoomQueryControllerDocs {
  private final ChatRoomQueryService service;

  @GetMapping
  public ApiResponse<ChatRoomPageResponse> getChatRooms(
      @AuthenticationPrincipal AuthenticatedMember member,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.success(service.getChatRooms(member, page, size));
  }

  @GetMapping("/{chatRoomId}")
  public ApiResponse<ChatRoomDetailResponse> getChatRoom(
      @AuthenticationPrincipal AuthenticatedMember member, @PathVariable Long chatRoomId) {
    return ApiResponse.success(service.getChatRoom(member, chatRoomId));
  }
}
