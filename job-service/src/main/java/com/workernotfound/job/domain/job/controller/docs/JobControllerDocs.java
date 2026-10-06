package com.workernotfound.job.domain.job.controller.docs;

import com.workernotfound.job.domain.job.dto.request.CreateJobRequest;
import com.workernotfound.job.domain.job.dto.request.JobSearchRequest;
import com.workernotfound.job.domain.job.dto.request.OwnerJobSearchRequest;
import com.workernotfound.job.domain.job.dto.request.UpdatePaymentTermsRequest;
import com.workernotfound.job.domain.job.dto.response.JobCloseResponse;
import com.workernotfound.job.domain.job.dto.response.JobDetailResponse;
import com.workernotfound.job.domain.job.dto.response.JobPaymentChangeResponse;
import com.workernotfound.job.domain.job.dto.response.JobPaymentOrderResponse;
import com.workernotfound.job.domain.job.dto.response.JobSearchResponse;
import com.workernotfound.job.domain.job.dto.response.OwnerJobListResponse;
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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
            summary = "점주 본인 공고 목록",
            description = "Bearer 토큰 회원(OWNER)이 등록한 공고를 등록 최신순(같으면 ID 내림차순)으로 조회합니다. 결제 대기"
                    + "(PAYMENT_PENDING)·공개·매칭 중·마감 공고와 예치 차단 공고를 모두 포함하며, status로 한 상태만 거를 수 있습니다. "
                    + "점주 ID는 토큰으로만 정합니다. page 기본 0, size 기본 20·최대 100이며 범위 밖 페이지는 빈 목록입니다. "
                    + "confirmedCount는 확정(CONSUMED)된 모집 자리 수이고 applicantCount는 아직 제공하지 않아 null입니다. "
                    + "결제 진행 상태는 공고 결제 주문 조회 API로 확인합니다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "공고 목록 조회 성공",
                    content = @Content(schema = @Schema(implementation = OwnerJobListResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 status·page·size", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않은 요청", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "OWNER가 아님", content = @Content)
    })
    ResponseEntity<ApiResponse<OwnerJobListResponse>> getMyJobs(
            @Parameter(hidden = true) MemberClaims claims,
            @Valid OwnerJobSearchRequest request
    );

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
            summary = "공고 결제 조건 변경(점주 본인)",
            description = "결제 대기(PAYMENT_PENDING) 공고의 근무일·시작/종료 시각·익일 여부·기본 시급·시간당 추가 시급·모집 인원·"
                    + "지원 마감을 바꾸고 새 결제 주문을 요청합니다. 변경 후 전체 조건을 보냅니다. 새 주문이 확인·연결되기 전까지 공고는 "
                    + "이전 조건과 이전 주문을 유지하며(status=PENDING), 연결되면 조건이 적용됩니다(APPLIED). payment-service가 이전 주문의 "
                    + "교체를 거절하면(결제 확인 중·예치 완료·검토 필요) REJECTED로 끝나고 공고는 그대로입니다. 같은 Idempotency-Key의 같은 "
                    + "요청은 현재 처리 상태를 반환하고, 다른 요청에 같은 키를 쓰면 409입니다. 공개·마감 공고와 진행 중인 주문 생성이 있는 "
                    + "공고는 409입니다. 새 주문의 예치가 확인돼야 공개됩니다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "변경 요청 접수 또는 같은 키의 기존 요청 상태",
                    content = @Content(schema = @Schema(implementation = JobPaymentChangeResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 오류, 근무 시간(JOB-400-001)·지원 마감(JOB-400-002)·금액(JOB-400-004) 오류, 변경 없음(JOB-400-005)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않은 요청", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "OWNER가 아님", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "공고 없음 또는 본인 공고 아님", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "멱등 키 재사용(JOB-409-004), 변경할 수 없는 공고(JOB-409-014), 진행 중인 주문 생성(JOB-409-015)", content = @Content)
    })
    ResponseEntity<ApiResponse<JobPaymentChangeResponse>> changePaymentTerms(
            @Parameter(hidden = true) MemberClaims claims,
            Long id,
            @NotBlank @Size(max = 100) @Pattern(regexp = "[!-~]+") String idempotencyKey,
            @Valid UpdatePaymentTermsRequest request
    );

    @Operation(
            summary = "공고 재결제(점주 본인)",
            description = "결제 대기 공고의 연결된 주문과 같은 조건·같은 결제용 버전으로 새 결제 주문을 요청합니다. payment-service는 결과가 "
                    + "확인된 FAILED 주문만 같은 조건으로 교체하므로, READY 주문은 기존 주문으로 다시 결제하고 결제 확인 중·예치 완료·검토 필요 "
                    + "주문은 REJECTED가 됩니다. 응답과 멱등 규칙은 결제 조건 변경과 같습니다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "재결제 요청 접수 또는 같은 키의 기존 요청 상태",
                    content = @Content(schema = @Schema(implementation = JobPaymentChangeResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Idempotency-Key 누락·형식 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않은 요청", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "OWNER가 아님", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "공고 없음 또는 본인 공고 아님", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "멱등 키 재사용(JOB-409-004), 재결제할 수 없는 공고(JOB-409-014), 진행 중인 주문 생성(JOB-409-015)", content = @Content)
    })
    ResponseEntity<ApiResponse<JobPaymentChangeResponse>> retryPayment(
            @Parameter(hidden = true) MemberClaims claims,
            Long id,
            @NotBlank @Size(max = 100) @Pattern(regexp = "[!-~]+") String idempotencyKey
    );

    @Operation(
            summary = "공고 수동 마감(점주 본인)",
            description = "점주 본인 공고의 모집을 끝냅니다. OPEN·MATCHING 공고는 CLOSED로 바뀌고 남은 지원과 매칭 제안의 종료를 "
                    + "matching-service에 알립니다. 결제 대기(PAYMENT_PENDING) 공고는 알림 없이 CLOSED가 됩니다. 이미 확정된 매칭과 "
                    + "근무는 유지되며, 마감 뒤 도착한 모집 자리 첫 확정은 거절됩니다. 이미 마감된 공고는 바꾸지 않고 "
                    + "result=ALREADY_CLOSED로 성공합니다. 같은 Idempotency-Key의 같은 공고 요청은 처음 결과를 반환하고, 다른 공고에 "
                    + "같은 키를 쓰면 409입니다. 미결제 주문 정리, 예치 잔액 환불, 재오픈은 하지 않습니다.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "마감 처리(CLOSED), 이미 마감된 공고(ALREADY_CLOSED) 또는 같은 키의 처음 결과",
                    content = @Content(schema = @Schema(implementation = JobCloseResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Idempotency-Key 누락·형식 오류", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않은 요청", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "OWNER가 아님", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "공고 없음 또는 본인 공고 아님", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "멱등 키 재사용(JOB-409-004)", content = @Content)
    })
    ResponseEntity<ApiResponse<JobCloseResponse>> close(
            @Parameter(hidden = true) MemberClaims claims,
            Long id,
            @NotBlank @Size(max = 100) @Pattern(regexp = "[!-~]+") String idempotencyKey
    );

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
