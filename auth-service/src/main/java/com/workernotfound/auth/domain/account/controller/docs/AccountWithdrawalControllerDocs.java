package com.workernotfound.auth.domain.account.controller.docs;

import com.workernotfound.auth.domain.account.service.WithdrawalTransactionService;
import com.workernotfound.auth.domain.token.service.AuthTokenClaims;
import com.workernotfound.auth.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

@SecurityRequirement(name="bearerAuth")
public interface AccountWithdrawalControllerDocs {
    @Operation(summary="회원 탈퇴",description="진행 중 지원·매칭·근무·결제는 탈퇴를 차단합니다. UUID 멱등 키로 처리 결과를 추적합니다. 성공 후 기존 토큰은 거절됩니다.")
    ResponseEntity<ApiResponse<WithdrawalTransactionService.State>> withdraw(@Parameter(hidden=true) AuthTokenClaims claims, UUID key);
    @Operation(summary="탈퇴 처리 상태 조회")
    ResponseEntity<ApiResponse<WithdrawalTransactionService.State>> get(@Parameter(hidden=true) AuthTokenClaims claims, UUID key);
}
