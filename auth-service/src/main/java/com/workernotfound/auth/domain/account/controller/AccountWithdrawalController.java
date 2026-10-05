package com.workernotfound.auth.domain.account.controller;
import com.workernotfound.auth.domain.account.service.*;
import com.workernotfound.auth.domain.token.service.AuthTokenClaims;
import com.workernotfound.auth.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/account/withdrawals")
public class AccountWithdrawalController implements com.workernotfound.auth.domain.account.controller.docs.AccountWithdrawalControllerDocs {
    private final WithdrawalTransactionService transactions;
    private final WithdrawalDispatcher dispatcher;
    @Override @PostMapping
    public ResponseEntity<ApiResponse<WithdrawalTransactionService.State>> withdraw(@AuthenticationPrincipal AuthTokenClaims claims,
            @RequestHeader("Idempotency-Key") UUID key) {
        transactions.submit(claims,key.toString());
        dispatcher.dispatch(key.toString());
        return ResponseEntity.accepted().body(ApiResponse.success(HttpStatus.ACCEPTED,transactions.get(claims,key.toString())));
    }
    @Override @GetMapping("/{key}")
    public ResponseEntity<ApiResponse<WithdrawalTransactionService.State>> get(@AuthenticationPrincipal AuthTokenClaims claims,
            @PathVariable UUID key) {
        return ResponseEntity.ok(ApiResponse.success(transactions.get(claims,key.toString())));
    }
}
