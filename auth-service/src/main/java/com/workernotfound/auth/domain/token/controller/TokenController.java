package com.workernotfound.auth.domain.token.controller;

import com.workernotfound.auth.domain.token.controller.docs.TokenControllerDocs;
import com.workernotfound.auth.domain.token.dto.request.TokenReissueRequest;
import com.workernotfound.auth.domain.token.dto.response.TokenResponse;
import com.workernotfound.auth.domain.token.service.TokenService;
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
@RequestMapping("/api/auth/tokens")
public class TokenController implements TokenControllerDocs {

	private final TokenService tokenService;

	@Override
	@PostMapping("/reissue")
	public ResponseEntity<ApiResponse<TokenResponse>> reissue(
		@Valid @RequestBody TokenReissueRequest request
	) {
		return ResponseEntity.ok(ApiResponse.success(tokenService.reissue(request)));
	}
}
