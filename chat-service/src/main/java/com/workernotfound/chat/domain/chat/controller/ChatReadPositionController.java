package com.workernotfound.chat.domain.chat.controller;

import com.workernotfound.chat.domain.chat.controller.docs.ChatReadPositionControllerDocs;
import com.workernotfound.chat.domain.chat.dto.request.ChatReadPositionRequest;
import com.workernotfound.chat.domain.chat.dto.response.ChatReadPositionResponse;
import com.workernotfound.chat.domain.chat.service.ChatReadPositionService;
import com.workernotfound.chat.global.response.ApiResponse;
import com.workernotfound.chat.global.security.AuthenticatedMember;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat-rooms/me/{chatRoomId}/read-position")
public class ChatReadPositionController implements ChatReadPositionControllerDocs {
  private final ChatReadPositionService service;

  @PutMapping
  public ApiResponse<ChatReadPositionResponse> advanceReadPosition(
      @AuthenticationPrincipal AuthenticatedMember member,
      @PathVariable Long chatRoomId, @RequestBody ChatReadPositionRequest request) {
    return ApiResponse.success(service.advanceReadPosition(member, chatRoomId, request));
  }
}
