package com.workernotfound.job.domain.job.controller.docs;

import com.workernotfound.job.domain.job.dto.request.ApplicationAdmissionRequest;
import com.workernotfound.job.domain.job.dto.response.ApplicationAdmissionResponse;
import com.workernotfound.job.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;

@Tag(name = "Job Internal", description = "서비스 간 공고 내부 API (X-Internal-Secret, Idempotency-Key 필수)")
public interface JobInternalControllerDocs {

    @Operation(
            summary = "지원 접수 승인 발급",
            description = "공고가 OPEN이고 지원 마감 전일 때 단기 지원 접수 승인을 발급합니다. "
                    + "같은 Idempotency-Key 재요청은 기존 승인을 반환하며, 모집 자리는 차감하지 않습니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "승인 발급 성공",
                    content = @Content(schema = @Schema(implementation = ApplicationAdmissionResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "내부 인증 실패", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "공고 없음 (JOB-404-001)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "모집 중이 아님(JOB-409-001), 지원 마감(JOB-409-002), 승인 만료(JOB-409-003), 멱등 키 재사용(JOB-409-004)",
                    content = @Content
            )
    })
    ResponseEntity<ApiResponse<ApplicationAdmissionResponse>> createApplicationAdmission(
            @Positive Long jobPostId,
            @NotBlank @Size(max = 100) @Pattern(regexp = "[!-~]+") String idempotencyKey,
            @Valid ApplicationAdmissionRequest request
    );
}
