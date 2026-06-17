package com.workernotfound.job.domain.job.controller.docs;

import com.workernotfound.job.domain.job.dto.request.CreateJobRequest;
import com.workernotfound.job.domain.job.dto.request.JobSearchRequest;
import com.workernotfound.job.domain.job.dto.response.JobDetailResponse;
import com.workernotfound.job.domain.job.dto.response.JobSearchResponse;
import com.workernotfound.job.global.config.OpenApiConfig;
import com.workernotfound.job.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Job", description = "공고 API")
public interface JobControllerDocs {

    @Operation(
            summary = "공고 등록",
            description = "새로운 구인 공고를 등록합니다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "공고 등록 성공",
                    content = @Content(schema = @Schema(implementation = Long.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않은 요청", content = @Content)
    })
    ResponseEntity<ApiResponse<Long>> create(Long ownerId, CreateJobRequest request);

    @Operation(
            summary = "공고 상세 조회",
            description = "공고 ID로 상세 정보를 조회합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "공고 상세 조회 성공",
                    content = @Content(schema = @Schema(implementation = JobDetailResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "공고 없음", content = @Content)
    })
    ResponseEntity<ApiResponse<JobDetailResponse>> getDetail(Long id);

    @Operation(
            summary = "공고 목록 조회",
            description = "조건에 맞는 공고 목록을 조회합니다. type=URGENT이면 긴급 공고(urgencyLevel=HIGH)를 마감 임박 순으로 반환합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "공고 목록 조회 성공",
                    content = @Content(schema = @Schema(implementation = JobSearchResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content)
    })
    ResponseEntity<ApiResponse<JobSearchResponse>> search(JobSearchRequest request);
}
