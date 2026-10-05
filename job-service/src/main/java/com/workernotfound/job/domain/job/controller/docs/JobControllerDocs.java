package com.workernotfound.job.domain.job.controller.docs;

import com.workernotfound.job.domain.job.dto.request.CreateJobRequest;
import com.workernotfound.job.domain.job.dto.request.JobSearchRequest;
import com.workernotfound.job.domain.job.dto.response.JobDetailResponse;
import com.workernotfound.job.domain.job.dto.response.JobPaymentOrderResponse;
import com.workernotfound.job.domain.job.dto.response.JobSearchResponse;
import com.workernotfound.job.global.config.OpenApiConfig;
import com.workernotfound.job.global.response.ApiResponse;
import com.workernotfound.job.global.security.MemberClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
    // 제약은 이 인터페이스에만 둔다. 구현 메서드가 매개변수 제약을 다시 선언하면 메서드 검증이 거절한다.
    ResponseEntity<ApiResponse<Long>> create(MemberClaims claims, @Valid CreateJobRequest request);

    @Operation(
            summary = "공고 상세 조회",
            description = "공고 ID로 상세 정보를 조회합니다. 결제 대기(PAYMENT_PENDING) 공고는 Bearer 토큰의 회원이 "
                    + "점주 본인일 때만 조회되며, 그 밖의 요청은 404입니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "공고 상세 조회 성공",
                    content = @Content(schema = @Schema(implementation = JobDetailResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "공고 없음", content = @Content)
    })
    ResponseEntity<ApiResponse<JobDetailResponse>> getDetail(@Parameter(hidden = true) MemberClaims claims, Long id);

    @Operation(
            summary = "공고 결제 주문 조회(점주 본인)",
            description = "공고 등록 후 결제 주문 생성 상태를 조회합니다. orderCreationStatus가 PENDING이면 주문 생성이 아직 "
                    + "완료되지 않아 paymentOrderId가 null이며, CREATED가 되면 결제에 사용할 paymentOrderId가 채워집니다. "
                    + "주문 생성 완료는 공고 공개가 아닙니다. 다른 회원의 공고는 404입니다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "결제 주문 조회 성공",
                    content = @Content(schema = @Schema(implementation = JobPaymentOrderResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않은 요청", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "OWNER가 아님", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "공고 없음 또는 본인 공고 아님", content = @Content)
    })
    ResponseEntity<ApiResponse<JobPaymentOrderResponse>> getPaymentOrder(@Parameter(hidden = true) MemberClaims claims, Long id);

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
    ResponseEntity<ApiResponse<JobSearchResponse>> search(@Valid JobSearchRequest request);
}
