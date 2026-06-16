package com.workernotfound.auth.domain.auth.controller.docs;

import com.workernotfound.auth.domain.auth.dto.request.SendEmailVerificationRequest;
import com.workernotfound.auth.domain.auth.dto.request.SendSmsVerificationRequest;
import com.workernotfound.auth.domain.auth.dto.request.VerifyEmailRequest;
import com.workernotfound.auth.domain.auth.dto.request.VerifySmsRequest;
import com.workernotfound.auth.domain.auth.dto.response.VerificationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Verification", description = "인증번호 API")
public interface VerificationControllerDocs {

	@Operation(summary = "이메일 인증번호 발송", description = "회원가입용 이메일 인증번호를 발송합니다.")
	@ApiResponses({
		@ApiResponse(
			responseCode = "200",
			description = "이메일 인증번호 발송 성공",
			content = @Content(schema = @Schema(implementation = VerificationResponse.class))
		),
		@ApiResponse(responseCode = "400", description = "요청 검증 실패", content = @Content)
	})
	ResponseEntity<VerificationResponse> sendEmailVerification(SendEmailVerificationRequest request);

	@Operation(summary = "이메일 인증번호 검증", description = "회원가입용 이메일 인증번호를 검증합니다.")
	@ApiResponses({
		@ApiResponse(
			responseCode = "200",
			description = "이메일 인증번호 검증 결과",
			content = @Content(schema = @Schema(implementation = VerificationResponse.class))
		),
		@ApiResponse(responseCode = "400", description = "요청 검증 실패", content = @Content)
	})
	ResponseEntity<VerificationResponse> verifyEmail(VerifyEmailRequest request);

	@Operation(summary = "SMS 인증번호 발송", description = "회원가입용 SMS 인증번호를 발송합니다.")
	@ApiResponses({
		@ApiResponse(
			responseCode = "200",
			description = "SMS 인증번호 발송 성공",
			content = @Content(schema = @Schema(implementation = VerificationResponse.class))
		),
		@ApiResponse(responseCode = "400", description = "요청 검증 실패", content = @Content)
	})
	ResponseEntity<VerificationResponse> sendSmsVerification(SendSmsVerificationRequest request);

	@Operation(summary = "SMS 인증번호 검증", description = "회원가입용 SMS 인증번호를 검증합니다.")
	@ApiResponses({
		@ApiResponse(
			responseCode = "200",
			description = "SMS 인증번호 검증 결과",
			content = @Content(schema = @Schema(implementation = VerificationResponse.class))
		),
		@ApiResponse(responseCode = "400", description = "요청 검증 실패", content = @Content)
	})
	ResponseEntity<VerificationResponse> verifySms(VerifySmsRequest request);
}
