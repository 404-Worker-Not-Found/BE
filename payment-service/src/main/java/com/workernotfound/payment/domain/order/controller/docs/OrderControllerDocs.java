package com.workernotfound.payment.domain.order.controller.docs;

import com.workernotfound.payment.domain.order.dto.*;
import com.workernotfound.payment.global.response.ApiResponse;
import com.workernotfound.payment.global.security.AuthenticatedMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@Tag(name = "예치 결제", description = "토스 테스트 카드·KRW. 공고 서비스의 서버 계산 금액으로 주문을 생성합니다.")
public interface OrderControllerDocs {
  @Operation(summary = "공고 결제 주문 생성", description = "job-service 내부 전용. 동일 키는 원래 주문을 반환합니다.")
  @SecurityRequirement(name = "internalSecret")
  ApiResponse<OrderResponse> create(
      @NotBlank @Size(max = 128) @Pattern(regexp = "[!-~]+") String key,
      @Valid CreateOrderRequest request);

  @Operation(summary = "본인 예치 주문 조회")
  @SecurityRequirement(name = "bearerAuth")
  ApiResponse<OrderResponse> getOrder(
      @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]{6,64}") String orderId,
      AuthenticatedMember member);

  @Operation(summary = "본인 예치 결제 승인", description = "저장 금액과 비교합니다. 응답 유실 시 같은 paymentKey로 재시도합니다.")
  @SecurityRequirement(name = "bearerAuth")
  ApiResponse<OrderResponse> confirm(
      @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]{6,64}") String orderId,
      AuthenticatedMember member,
      @Valid ConfirmOrderRequest request);

  @Operation(
      summary = "토스 결제 상태 알림",
      description = "본문 상태는 신뢰하지 않고 저장된 paymentKey에 대한 서버 조회를 예약합니다.")
  ApiResponse<Void> webhook(@Valid TossWebhook event);
}
