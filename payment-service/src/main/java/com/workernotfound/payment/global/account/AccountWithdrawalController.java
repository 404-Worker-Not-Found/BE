package com.workernotfound.payment.global.account;

import com.workernotfound.payment.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments/internal/account-withdrawals")
public class AccountWithdrawalController implements AccountWithdrawalControllerDocs {
  private final AccountGateService gates;
  private final AccountInternalAuthorization authorization;
    public record Request(@NotNull UUID commandId) {}
    @Override
    @PostMapping("/{memberId}/{action:prepare|release|commit}")
    public ResponseEntity<ApiResponse<AccountGateService.Result>> transition(@PathVariable Long memberId,
            @PathVariable String action, @RequestHeader(value="X-Internal-Secret", required=false) String secret, @Valid @RequestBody Request request) {
        authorization.verify(secret);
        return ResponseEntity.ok(ApiResponse.success(gates.transition(memberId, request.commandId().toString(), action)));
    }
}

interface AccountWithdrawalControllerDocs {
    @Operation(summary = "회원 탈퇴 준비·보상·확정", description = "공유 시크릿으로 보호되는 내부 계약입니다. 명령 ID별 결과를 영속적으로 저장합니다.")
    ResponseEntity<ApiResponse<AccountGateService.Result>> transition(Long memberId, String action, String secret, AccountWithdrawalController.Request request);
}
