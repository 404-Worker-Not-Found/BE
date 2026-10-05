package com.workernotfound.matching.global.account;

import com.workernotfound.matching.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/applications/internal/account-withdrawals")
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
