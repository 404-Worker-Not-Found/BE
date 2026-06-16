package com.workernotfound.auth.domain.auth.controller;

import com.workernotfound.auth.domain.auth.controller.docs.VerificationControllerDocs;
import com.workernotfound.auth.domain.auth.dto.request.SendEmailVerificationRequest;
import com.workernotfound.auth.domain.auth.dto.request.SendSmsVerificationRequest;
import com.workernotfound.auth.domain.auth.dto.request.VerifyEmailRequest;
import com.workernotfound.auth.domain.auth.dto.request.VerifySmsRequest;
import com.workernotfound.auth.domain.auth.dto.response.VerificationResponse;
import com.workernotfound.auth.domain.auth.entity.enums.VerificationPurpose;
import com.workernotfound.auth.domain.auth.service.VerificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class VerificationController implements VerificationControllerDocs {

	private final VerificationService verificationService;

	@Override
	@PostMapping("/email-verifications/send")
	public ResponseEntity<VerificationResponse> sendEmailVerification(
		@Valid @RequestBody SendEmailVerificationRequest request
	) {
		verificationService.sendEmailVerificationCode(VerificationPurpose.SIGNUP, request.email());
		return ResponseEntity.ok(new VerificationResponse(false, "이메일 인증번호를 발송했습니다."));
	}

	@Override
	@PostMapping("/email-verifications/verify")
	public ResponseEntity<VerificationResponse> verifyEmail(
		@Valid @RequestBody VerifyEmailRequest request
	) {
		boolean verified = verificationService.verifyEmailCode(
			VerificationPurpose.SIGNUP,
			request.email(),
			request.verificationCode()
		);
		return ResponseEntity.ok(new VerificationResponse(verified, verificationMessage(verified)));
	}

	@Override
	@PostMapping("/sms-verifications/send")
	public ResponseEntity<VerificationResponse> sendSmsVerification(
		@Valid @RequestBody SendSmsVerificationRequest request
	) {
		verificationService.sendSmsVerificationCode(VerificationPurpose.SIGNUP, request.phoneNumber());
		return ResponseEntity.ok(new VerificationResponse(false, "SMS 인증번호를 발송했습니다."));
	}

	@Override
	@PostMapping("/sms-verifications/verify")
	public ResponseEntity<VerificationResponse> verifySms(
		@Valid @RequestBody VerifySmsRequest request
	) {
		boolean verified = verificationService.verifySmsCode(
			VerificationPurpose.SIGNUP,
			request.phoneNumber(),
			request.verificationCode()
		);
		return ResponseEntity.ok(new VerificationResponse(verified, verificationMessage(verified)));
	}

	private String verificationMessage(boolean verified) {
		if (verified) {
			return "인증이 완료되었습니다.";
		}
		return "인증번호가 올바르지 않거나 만료되었습니다.";
	}
}
