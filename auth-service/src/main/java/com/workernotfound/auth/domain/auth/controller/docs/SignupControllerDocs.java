package com.workernotfound.auth.domain.auth.controller.docs;

import com.workernotfound.auth.domain.auth.dto.request.OwnerSignupRequest;
import com.workernotfound.auth.domain.auth.dto.request.WorkerSignupRequest;
import com.workernotfound.auth.domain.auth.dto.response.SignupResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Signup", description = "회원가입 API")
public interface SignupControllerDocs {

	@Operation(summary = "OWNER 회원가입", description = "이메일/SMS 인증 완료 후 OWNER 회원가입을 처리합니다.")
	@ApiResponses({
		@ApiResponse(
			responseCode = "201",
			description = "OWNER 회원가입 성공",
			content = @Content(schema = @Schema(implementation = SignupResponse.class))
		),
		@ApiResponse(responseCode = "400", description = "회원가입 요청 검증 실패", content = @Content)
	})
	ResponseEntity<com.workernotfound.auth.global.response.ApiResponse<SignupResponse>> signupOwner(OwnerSignupRequest request);

	@Operation(summary = "WORKER 회원가입", description = "이메일/SMS 인증 완료 후 WORKER 회원가입을 처리합니다.")
	@ApiResponses({
		@ApiResponse(
			responseCode = "201",
			description = "WORKER 회원가입 성공",
			content = @Content(schema = @Schema(implementation = SignupResponse.class))
		),
		@ApiResponse(responseCode = "400", description = "회원가입 요청 검증 실패", content = @Content)
	})
	ResponseEntity<com.workernotfound.auth.global.response.ApiResponse<SignupResponse>> signupWorker(WorkerSignupRequest request);
}
