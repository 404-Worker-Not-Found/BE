package com.workernotfound.job.global.account;
import com.workernotfound.job.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
public interface AccountWithdrawalControllerDocs {
    @Operation(summary = "회원 탈퇴 준비·보상·확정", description = "공유 시크릿으로 보호되는 내부 계약입니다. 명령 ID별 결과를 영속적으로 저장합니다.")
    ResponseEntity<ApiResponse<AccountGateService.Result>> transition(Long memberId, String action, String secret, AccountWithdrawalController.Request request);
}
