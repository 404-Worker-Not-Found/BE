package com.workernotfound.auth.domain.auth.controller;

import com.workernotfound.auth.domain.auth.controller.docs.PasswordResetControllerDocs;
import com.workernotfound.auth.domain.auth.dto.request.PasswordResetRequest;
import com.workernotfound.auth.domain.auth.dto.request.SendEmailVerificationRequest;
import com.workernotfound.auth.domain.auth.dto.response.VerificationResponse;
import com.workernotfound.auth.domain.auth.service.PasswordResetService;
import com.workernotfound.auth.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/password-resets")
public class PasswordResetController implements PasswordResetControllerDocs {

	private final PasswordResetService passwordResetService;

	@Override
	@PostMapping("/email-verifications/send")
	public ResponseEntity<ApiResponse<VerificationResponse>> sendVerificationCode(
			@Valid @RequestBody SendEmailVerificationRequest request) {
		passwordResetService.sendVerificationCode(request.email());
		return ResponseEntity.ok(ApiResponse.success(new VerificationResponse(false,
				"재설정 가능한 계정이면 이메일 인증번호를 발송했습니다.")));
	}

	@Override
	@PostMapping
	public ResponseEntity<ApiResponse<Void>> resetPassword(
			@Valid @RequestBody PasswordResetRequest request) {
		passwordResetService.resetPassword(request);
		return ResponseEntity.ok(ApiResponse.success(null));
	}
}
