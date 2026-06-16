package com.workernotfound.auth.domain.auth.controller.docs;

import com.workernotfound.auth.domain.account.entity.enums.OAuthProvider;
import com.workernotfound.auth.domain.auth.dto.request.OAuthLoginRequest;
import com.workernotfound.auth.domain.auth.dto.request.OAuthOwnerSignupRequest;
import com.workernotfound.auth.domain.auth.dto.request.OAuthWorkerSignupRequest;
import com.workernotfound.auth.domain.auth.dto.response.OAuthLoginResponse;
import com.workernotfound.auth.domain.auth.dto.response.SignupResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "OAuth2 Auth", description = "KAKAO/NAVER OAuth2 인증 API")
public interface OAuthControllerDocs {

	@Operation(
		summary = "OAuth2 로그인",
		description = "KAKAO 또는 NAVER 인가 코드로 로그인합니다. 미가입 계정이면 signup ticket을 반환합니다."
	)
	@ApiResponses({
		@ApiResponse(
			responseCode = "200",
			description = "OAuth2 로그인 성공 또는 signup ticket 발급",
			content = @Content(schema = @Schema(implementation = OAuthLoginResponse.class))
		),
		@ApiResponse(responseCode = "400", description = "OAuth2 로그인 요청 검증 실패", content = @Content)
	})
	ResponseEntity<OAuthLoginResponse> login(OAuthProvider provider, OAuthLoginRequest request);

	@Operation(summary = "OAuth2 OWNER 회원가입 완료", description = "OAuth2 signup ticket과 OWNER 추가 정보로 가입을 완료합니다.")
	@ApiResponses({
		@ApiResponse(
			responseCode = "201",
			description = "OAuth2 OWNER 회원가입 성공",
			content = @Content(schema = @Schema(implementation = SignupResponse.class))
		),
		@ApiResponse(responseCode = "400", description = "OAuth2 OWNER 회원가입 요청 검증 실패", content = @Content)
	})
	ResponseEntity<SignupResponse> signupOwner(OAuthOwnerSignupRequest request);

	@Operation(summary = "OAuth2 WORKER 회원가입 완료", description = "OAuth2 signup ticket과 WORKER 추가 정보로 가입을 완료합니다.")
	@ApiResponses({
		@ApiResponse(
			responseCode = "201",
			description = "OAuth2 WORKER 회원가입 성공",
			content = @Content(schema = @Schema(implementation = SignupResponse.class))
		),
		@ApiResponse(responseCode = "400", description = "OAuth2 WORKER 회원가입 요청 검증 실패", content = @Content)
	})
	ResponseEntity<SignupResponse> signupWorker(OAuthWorkerSignupRequest request);
}
