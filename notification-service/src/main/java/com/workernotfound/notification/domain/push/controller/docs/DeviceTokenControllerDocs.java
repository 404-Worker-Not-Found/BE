package com.workernotfound.notification.domain.push.controller.docs;

import com.workernotfound.notification.domain.push.dto.request.DeviceTokenRequest;
import com.workernotfound.notification.domain.push.dto.response.DeviceTokenResponse;

import com.workernotfound.notification.global.response.ApiResponse;
import com.workernotfound.notification.global.security.AuthenticatedMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

@Tag(name = "푸시 기기", description = "본인 Android FCM 토큰 관리. 토큰 원문은 응답하지 않습니다.")
@SecurityRequirement(name = "bearerAuth")
public interface DeviceTokenControllerDocs {
  @Operation(summary = "FCM 토큰 등록·재등록", description = "로그인 및 토큰 갱신 때 호출합니다. 같은 토큰은 현재 계정·역할에 연결됩니다.")
  ApiResponse<DeviceTokenResponse> register(@Parameter(hidden = true) AuthenticatedMember member, @Valid DeviceTokenRequest request);

  @Operation(summary = "FCM 토큰 해제", description = "로그아웃 전 호출합니다. 타인·다른 역할·없는 ID도 성공하며 변경하지 않습니다.")
  ApiResponse<Void> remove(@Parameter(hidden = true) AuthenticatedMember member, @Positive long deviceTokenId);
}
