package com.workernotfound.auth.domain.account.controller.docs;

import com.workernotfound.auth.domain.account.dto.*;
import com.workernotfound.auth.domain.auth.dto.request.*;
import com.workernotfound.auth.domain.token.service.AuthTokenClaims;
import com.workernotfound.auth.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

@SecurityRequirement(name = "bearerAuth")
public interface AccountContactControllerDocs {
	@Operation(summary = "이메일 변경 인증번호 발송")
	ResponseEntity<ApiResponse<Void>> sendEmail(SendEmailVerificationRequest request);
	@Operation(summary = "전화번호 변경 인증번호 발송")
	ResponseEntity<ApiResponse<Void>> sendSms(SendSmsVerificationRequest request);
	@Operation(summary = "연락처 변경", description = "변경 대상 인증번호를 일회 소비합니다. 모든 refresh token을 폐기하며 동기화 완료 후 다시 로그인합니다.")
	ResponseEntity<ApiResponse<AccountChangeResponse>> changeContact(@Parameter(hidden = true) AuthTokenClaims claims, UUID key, ContactChangeRequest request);
	@Operation(summary = "연락처 변경 처리 상태")
	ResponseEntity<ApiResponse<AccountChangeResponse>> getCommand(@Parameter(hidden = true) AuthTokenClaims claims, UUID id);
}
