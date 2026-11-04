package com.workernotfound.notification.domain.push.controller;

import com.workernotfound.notification.domain.push.dto.request.DeviceTokenRequest;
import com.workernotfound.notification.domain.push.dto.response.DeviceTokenResponse;
import com.workernotfound.notification.domain.push.controller.docs.DeviceTokenControllerDocs;
import com.workernotfound.notification.domain.push.service.DeviceTokenService;

import com.workernotfound.notification.global.response.ApiResponse;
import com.workernotfound.notification.global.security.AuthenticatedMember;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications/me/device-tokens")
public class DeviceTokenController implements DeviceTokenControllerDocs {
  private final DeviceTokenService service;

  @PutMapping
  public ApiResponse<DeviceTokenResponse> register(@AuthenticationPrincipal AuthenticatedMember member,
      @RequestBody DeviceTokenRequest request) {
    return ApiResponse.success(service.register(member, request));
  }

  @DeleteMapping("/{deviceTokenId}")
  public ApiResponse<Void> remove(@AuthenticationPrincipal AuthenticatedMember member,
      @PathVariable long deviceTokenId) {
    service.remove(member, deviceTokenId);
    return ApiResponse.success(null);
  }
}
