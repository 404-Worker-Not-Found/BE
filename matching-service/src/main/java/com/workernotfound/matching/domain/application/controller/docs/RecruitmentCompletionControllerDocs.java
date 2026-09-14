package com.workernotfound.matching.domain.application.controller.docs;

import com.workernotfound.matching.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;

@Tag(name = "Internal Recruitment", description = "공고 모집 상태 연동 API")
public interface RecruitmentCompletionControllerDocs {

	@Operation(summary = "모집 완료 반영")
	@ApiResponses({
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "반영 성공"),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "내부 인증 실패", content = @Content),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "매칭 확정 처리 중", content = @Content)
	})
	ResponseEntity<ApiResponse<Void>> complete(
		@Parameter(description = "공고 ID", required = true)
		@Positive(message = "공고 ID는 양수여야 합니다.") Long jobPostId,
		@Parameter(description = "모집 완료 명령 ID", required = true)
		@NotBlank(message = "Idempotency-Key는 필수입니다.")
		@Size(max = 36, message = "Idempotency-Key는 36자 이하여야 합니다.") String correlationId
	);
}
