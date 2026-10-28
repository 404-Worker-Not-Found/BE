package com.workernotfound.payment.domain.settlement.controller.docs;

import com.workernotfound.payment.domain.settlement.dto.SettlementResponse;
import com.workernotfound.payment.global.response.ApiResponse;
import com.workernotfound.payment.global.security.AuthenticatedMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;
import java.util.List;

@Tag(name = "정산", description = "본인 역할에 해당하는 정산만 조회합니다. 금액은 KRW, 시각은 근무 시간대입니다.")
@SecurityRequirement(name = "bearerAuth")
public interface SettlementControllerDocs {
  @Operation(summary = "본인 정산 목록", description = "ID 내림차순. 마지막 ID를 beforeId로 보내 다음 페이지를 조회합니다.")
  ApiResponse<List<SettlementResponse>> getSettlements(AuthenticatedMember member, @Positive Long beforeId,
      @Min(1) @Max(100) int size);

  @Operation(summary = "본인 정산 상세", description = "다른 참여자의 정산은 404를 반환합니다.")
  ApiResponse<SettlementResponse> getSettlement(AuthenticatedMember member, @Positive long id);
}
