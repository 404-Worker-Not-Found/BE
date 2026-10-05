package com.workernotfound.auth.domain.auth.controller.docs;

import com.workernotfound.auth.domain.auth.dto.request.PasswordResetRequest;
import com.workernotfound.auth.domain.auth.dto.request.SendEmailVerificationRequest;
import com.workernotfound.auth.domain.auth.dto.response.VerificationResponse;
import com.workernotfound.auth.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Password Reset", description = "이메일 인증 기반 계정 복구 API")
public interface PasswordResetControllerDocs {

	@Operation(summary = "비밀번호 재설정 인증번호 발송",
			description = "활성 LOCAL 비밀번호가 있는 계정에 5분 유효 인증번호를 발송합니다. 계정 존재 여부를 응답하지 않습니다.")
	@ApiResponses({
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "요청 접수"),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 검증 실패"),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "발송 제한"),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "이메일 발송 실패")
	})
	ResponseEntity<ApiResponse<VerificationResponse>> sendVerificationCode(SendEmailVerificationRequest request);

	@Operation(summary = "비밀번호 재설정",
			description = "인증번호와 새 비밀번호(8~100자, UTF-8 최대 72바이트)를 함께 제출합니다. 인증번호는 일회용이며 모든 refresh token을 폐기합니다. 기존 access token은 만료까지 유효합니다.")
	@ApiResponses({
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재설정 완료"),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 또는 재설정 요청이 유효하지 않음")
	})
	ResponseEntity<ApiResponse<Void>> resetPassword(PasswordResetRequest request);
}
