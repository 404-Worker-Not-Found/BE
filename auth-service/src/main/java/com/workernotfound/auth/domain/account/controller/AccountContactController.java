package com.workernotfound.auth.domain.account.controller;

import com.workernotfound.auth.domain.account.dto.ContactChangeRequest.AccountChangeResponse;
import com.workernotfound.auth.domain.account.controller.docs.AccountContactControllerDocs;
import com.workernotfound.auth.domain.account.dto.*;
import com.workernotfound.auth.domain.account.service.*;
import com.workernotfound.auth.domain.auth.dto.request.SendEmailVerificationRequest;
import com.workernotfound.auth.domain.auth.dto.request.SendSmsVerificationRequest;
import com.workernotfound.auth.domain.auth.entity.enums.VerificationPurpose;
import com.workernotfound.auth.domain.auth.service.VerificationService;
import com.workernotfound.auth.domain.token.service.AuthTokenClaims;
import com.workernotfound.auth.global.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/account")
public class AccountContactController implements AccountContactControllerDocs {
	private final VerificationService verification;
	private final ContactChangeTransactionService transactions;
	private final ContactChangeDispatcher dispatcher;

	@Override
	@PostMapping("/email-verifications/send")
	public ResponseEntity<ApiResponse<Void>> sendEmail(@Valid @RequestBody SendEmailVerificationRequest request) {
		verification.sendEmailVerificationCode(VerificationPurpose.CONTACT_CHANGE, request.email());
		return ResponseEntity.ok(ApiResponse.success(null));
	}
	@Override
	@PostMapping("/sms-verifications/send")
	public ResponseEntity<ApiResponse<Void>> sendSms(@Valid @RequestBody SendSmsVerificationRequest request) {
		verification.sendSmsVerificationCode(VerificationPurpose.CONTACT_CHANGE, request.phoneNumber());
		return ResponseEntity.ok(ApiResponse.success(null));
	}
	@Override
	@PostMapping("/contact-changes")
	public ResponseEntity<ApiResponse<AccountChangeResponse>> changeContact(@AuthenticationPrincipal AuthTokenClaims claims,
			@RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody ContactChangeRequest request) {
		var response = transactions.submit(claims, key.toString(), request);
		dispatcher.dispatch(response.commandId());
		return ResponseEntity.accepted().body(ApiResponse.success(HttpStatus.ACCEPTED,
				transactions.getCommand(claims, response.commandId())));
	}
	@Override
	@GetMapping("/contact-changes/{id}")
	public ResponseEntity<ApiResponse<AccountChangeResponse>> getCommand(@AuthenticationPrincipal AuthTokenClaims claims,
			@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.success(transactions.getCommand(claims, id.toString())));
	}
}
