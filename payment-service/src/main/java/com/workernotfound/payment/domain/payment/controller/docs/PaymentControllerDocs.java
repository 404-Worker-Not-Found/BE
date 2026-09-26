package com.workernotfound.payment.domain.payment.controller.docs;

import com.workernotfound.payment.domain.payment.dto.request.PaymentLockRequest;
import com.workernotfound.payment.domain.payment.dto.response.PaymentLockResponse;
import com.workernotfound.payment.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "internalSecret")
@Tag(name = "결제 내부 API", description = "X-Internal-Secret 및 Idempotency-Key 필수")
public interface PaymentControllerDocs {
  @Operation(summary = "매칭 급여 잠금", description = "검증된 예치 잔액에서 잠급니다. 동일 키·요청은 원래 paymentId를 반환합니다.")
  ApiResponse<PaymentLockResponse> lock(
      @NotBlank @Size(max = 128) @Pattern(regexp = "[!-~]+") String key,
      @Valid PaymentLockRequest request);

  @Operation(summary = "결제 잠금 Saga 보상", description = "예치 잔액으로 반환합니다. PG 환불이 아니며 이미 해제된 잠금은 재처리하지 않습니다.")
  ApiResponse<Void> release(
      @NotBlank @Size(max = 128) @Pattern(regexp = "[!-~]+") String key, @Positive Long paymentId);
}
