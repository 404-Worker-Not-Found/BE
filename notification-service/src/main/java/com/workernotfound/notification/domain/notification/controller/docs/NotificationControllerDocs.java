package com.workernotfound.notification.domain.notification.controller.docs;

import com.workernotfound.notification.domain.notification.dto.response.*;
import com.workernotfound.notification.global.response.ApiResponse;
import com.workernotfound.notification.global.security.AuthenticatedMember;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;

@Tag(name = "내 알림", description = "본인 역할의 매칭·지원 결과 인앱 알림")
@SecurityRequirement(name = "bearerAuth")
public interface NotificationControllerDocs {
  @Operation(summary = "내 알림 목록", description = "저장 ID 내림차순. 다음 페이지는 nextBeforeId를 전달합니다.")
  ApiResponse<NotificationPageResponse> getNotifications(@Parameter(hidden = true) AuthenticatedMember member,
      @Positive Long beforeId, @Min(1) @Max(100) int size);

  @Operation(summary = "미읽음 알림 개수")
  ApiResponse<UnreadCountResponse> getUnreadCount(@Parameter(hidden = true) AuthenticatedMember member);

  @Operation(summary = "알림 읽음 처리", description = "반복 호출은 최초 읽음 시각을 유지합니다. 타인·다른 역할·없는 알림은 404입니다.")
  ApiResponse<NotificationResponse> markRead(@Parameter(hidden = true) AuthenticatedMember member,
      @Positive Long notificationId);
}
