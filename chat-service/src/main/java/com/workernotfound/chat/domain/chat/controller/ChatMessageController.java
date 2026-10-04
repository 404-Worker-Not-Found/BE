package com.workernotfound.chat.domain.chat.controller;

import com.workernotfound.chat.domain.chat.controller.docs.ChatMessageControllerDocs;
import com.workernotfound.chat.domain.chat.dto.request.ChatMessageRequest;
import com.workernotfound.chat.domain.chat.dto.response.*;
import com.workernotfound.chat.domain.chat.service.ChatMessageService;
import com.workernotfound.chat.global.response.ApiResponse;
import com.workernotfound.chat.global.security.AuthenticatedMember;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat-rooms/me/{chatRoomId}/messages")
public class ChatMessageController implements ChatMessageControllerDocs {
  private final ChatMessageService service;

  @PostMapping
  public ApiResponse<ChatMessageResponse> send(
      @AuthenticationPrincipal AuthenticatedMember member,
      @PathVariable Long chatRoomId, @RequestBody ChatMessageRequest request) {
    return ApiResponse.success(service.send(member, chatRoomId, request));
  }

  @GetMapping
  public ApiResponse<ChatMessagePageResponse> getMessages(
      @AuthenticationPrincipal AuthenticatedMember member, @PathVariable Long chatRoomId,
      @RequestParam(required = false) Long beforeId,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.success(service.getMessages(member, chatRoomId, beforeId, size));
  }
}
