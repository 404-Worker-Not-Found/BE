package com.workernotfound.auth.domain.auth.controller;

import com.workernotfound.auth.domain.auth.controller.docs.AuthControllerDocs;
import com.workernotfound.auth.domain.auth.dto.request.LoginRequest;
import com.workernotfound.auth.domain.auth.dto.response.LoginResponse;
import com.workernotfound.auth.domain.auth.service.LoginService;
import com.workernotfound.auth.domain.token.dto.request.LogoutRequest;
import com.workernotfound.auth.domain.token.service.LogoutService;
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
public class AuthController implements AuthControllerDocs {

	private final LoginService loginService;
	private final LogoutService logoutService;

	@Override
	@PostMapping("/login")
	public ResponseEntity<LoginResponse> login(
		@Valid @RequestBody LoginRequest request
	) {
		return ResponseEntity.ok(loginService.login(request));
	}

	@Override
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(
		@Valid @RequestBody LogoutRequest request
	) {
		logoutService.logout(request);
		return ResponseEntity.noContent().build();
	}
}
