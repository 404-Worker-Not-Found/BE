package com.workernotfound.payment.domain.refund.controller.docs;

import com.workernotfound.payment.domain.refund.dto.request.RefundRequest;
import com.workernotfound.payment.domain.refund.dto.response.RefundResponse;
import com.workernotfound.payment.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "internalSecret")
@Tag(name = "환불 내부 API", description = "X-Internal-Secret 필수, 환불 요청은 Idempotency-Key 필수")
public interface RefundControllerDocs {
  @Operation(summary = "예치금 환불 요청",
      description = "예치의 가용 잔액에서 환불 금액을 예약하고 REQUESTED 환불을 만듭니다. PG 환불은 아직 실행하지 않습니다."
          + " 동일 키·요청은 처음 만든 환불의 현재 상태를 반환합니다.")
  ApiResponse<RefundResponse> request(
      @NotBlank @Size(max = 128) @Pattern(regexp = "[!-~]+") String key,
      @Valid RefundRequest request);

  @Operation(summary = "예치금 환불 조회", description = "환불의 현재 상태를 조회합니다.")
  ApiResponse<RefundResponse> getRefund(@Positive Long refundId);
}
