package com.workernotfound.auth.domain.auth.controller.docs;

import com.workernotfound.auth.domain.auth.dto.request.LoginRequest;
import com.workernotfound.auth.domain.auth.dto.response.LoginResponse;
import com.workernotfound.auth.domain.token.dto.request.LogoutRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Auth", description = "인증 API")
public interface AuthControllerDocs {

	@Operation(summary = "LOCAL 로그인", description = "LOCAL 계정 이메일과 비밀번호로 로그인합니다.")
	@ApiResponses({
		@ApiResponse(
			responseCode = "200",
			description = "로그인 성공",
			content = @Content(schema = @Schema(implementation = LoginResponse.class))
		),
		@ApiResponse(responseCode = "400", description = "로그인 요청 검증 실패", content = @Content),
		@ApiResponse(responseCode = "401", description = "로그인 실패", content = @Content)
	})
	ResponseEntity<com.workernotfound.auth.global.response.ApiResponse<LoginResponse>> login(LoginRequest request);

	@Operation(summary = "로그아웃", description = "refresh token을 폐기합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "204", description = "로그아웃 성공", content = @Content),
		@ApiResponse(responseCode = "401", description = "인증되지 않은 요청", content = @Content)
	})
	ResponseEntity<com.workernotfound.auth.global.response.ApiResponse<Void>> logout(LogoutRequest request);
}
