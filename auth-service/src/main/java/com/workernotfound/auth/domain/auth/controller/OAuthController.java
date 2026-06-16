package com.workernotfound.auth.domain.auth.controller;

import com.workernotfound.auth.domain.account.entity.enums.OAuthProvider;
import com.workernotfound.auth.domain.auth.controller.docs.OAuthControllerDocs;
import com.workernotfound.auth.domain.auth.dto.request.OAuthLoginRequest;
import com.workernotfound.auth.domain.auth.dto.request.OAuthOwnerSignupRequest;
import com.workernotfound.auth.domain.auth.dto.request.OAuthWorkerSignupRequest;
import com.workernotfound.auth.domain.auth.dto.response.OAuthLoginResponse;
import com.workernotfound.auth.domain.auth.dto.response.SignupResponse;
import com.workernotfound.auth.domain.auth.service.OAuthLoginService;
import com.workernotfound.auth.domain.auth.service.SignupService;
import com.workernotfound.auth.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/oauth2")
public class OAuthController implements OAuthControllerDocs {

	private final OAuthLoginService oAuthLoginService;
	private final SignupService signupService;

	@Override
	@PostMapping("/{provider}/login")
	public ResponseEntity<ApiResponse<OAuthLoginResponse>> login(
		@PathVariable OAuthProvider provider,
		@Valid @RequestBody OAuthLoginRequest request
	) {
		return ResponseEntity.ok(ApiResponse.success(oAuthLoginService.login(provider, request)));
	}

	@Override
	@PostMapping("/signup/owner")
	public ResponseEntity<ApiResponse<SignupResponse>> signupOwner(
		@Valid @RequestBody OAuthOwnerSignupRequest request
	) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(HttpStatus.CREATED, signupService.signupOAuthOwner(request)));
	}

	@Override
	@PostMapping("/signup/worker")
	public ResponseEntity<ApiResponse<SignupResponse>> signupWorker(
		@Valid @RequestBody OAuthWorkerSignupRequest request
	) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(HttpStatus.CREATED, signupService.signupOAuthWorker(request)));
	}
}
