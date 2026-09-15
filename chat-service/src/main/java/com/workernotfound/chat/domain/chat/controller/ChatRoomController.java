package com.workernotfound.chat.domain.chat.controller;

import com.workernotfound.chat.domain.chat.controller.docs.ChatRoomControllerDocs;
import com.workernotfound.chat.domain.chat.dto.request.ChatRoomRequest;
import com.workernotfound.chat.domain.chat.dto.response.ChatRoomResponse;
import com.workernotfound.chat.domain.chat.service.ChatRoomApplicationService;
import com.workernotfound.chat.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat-rooms/internal")
public class ChatRoomController implements ChatRoomControllerDocs {
  private final ChatRoomApplicationService service;

  @PostMapping
  public ApiResponse<ChatRoomResponse> create(
      @RequestHeader("Idempotency-Key") String key, @RequestBody ChatRoomRequest request) {
    return ApiResponse.success(service.create(key, request));
  }

  @PostMapping("/{chatRoomId}/close")
  public ApiResponse<Void> close(
      @RequestHeader("Idempotency-Key") String key, @PathVariable Long chatRoomId) {
    service.close(key, chatRoomId);
    return ApiResponse.success(null);
  }
}
