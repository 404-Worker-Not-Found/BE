package com.workernotfound.notification.domain.notification.controller;

import com.workernotfound.notification.domain.notification.controller.docs.NotificationControllerDocs;
import com.workernotfound.notification.domain.notification.dto.response.*;
import com.workernotfound.notification.domain.notification.service.NotificationService;
import com.workernotfound.notification.global.response.ApiResponse;
import com.workernotfound.notification.global.security.AuthenticatedMember;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications/me")
public class NotificationController implements NotificationControllerDocs {
  private final NotificationService service;

  @GetMapping
  public ApiResponse<NotificationPageResponse> getNotifications(
      @AuthenticationPrincipal AuthenticatedMember member,
      @RequestParam(required = false) Long beforeId, @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.success(service.getNotifications(member, beforeId, size));
  }

  @GetMapping("/unread-count")
  public ApiResponse<UnreadCountResponse> getUnreadCount(@AuthenticationPrincipal AuthenticatedMember member) {
    return ApiResponse.success(service.getUnreadCount(member));
  }

  @PatchMapping("/{notificationId}/read")
  public ApiResponse<NotificationResponse> markRead(@AuthenticationPrincipal AuthenticatedMember member,
      @PathVariable Long notificationId) {
    return ApiResponse.success(service.markRead(member, notificationId));
  }
}
