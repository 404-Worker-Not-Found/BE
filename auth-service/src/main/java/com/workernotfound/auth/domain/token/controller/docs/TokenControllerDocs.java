package com.workernotfound.auth.domain.token.controller.docs;

import com.workernotfound.auth.domain.token.dto.request.TokenReissueRequest;
import com.workernotfound.auth.domain.token.dto.response.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Token", description = "토큰 API")
public interface TokenControllerDocs {

	@Operation(summary = "access token 재발급", description = "refresh token rotation으로 새 access/refresh token을 발급합니다.")
	@ApiResponses({
		@ApiResponse(
			responseCode = "200",
			description = "토큰 재발급 성공",
			content = @Content(schema = @Schema(implementation = TokenResponse.class))
		),
		@ApiResponse(responseCode = "400", description = "토큰 재발급 요청 검증 실패", content = @Content),
		@ApiResponse(responseCode = "401", description = "유효하지 않은 refresh token", content = @Content)
	})
	ResponseEntity<TokenResponse> reissue(TokenReissueRequest request);
}
