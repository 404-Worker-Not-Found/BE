package com.workernotfound.job.domain.job.controller.docs;

import com.workernotfound.job.domain.job.dto.request.ApplicationAdmissionRequest;
import com.workernotfound.job.domain.job.dto.request.FundingStatusRequest;
import com.workernotfound.job.domain.job.dto.request.MatchingSeatReservationRequest;
import com.workernotfound.job.domain.job.dto.response.ApplicationAdmissionResponse;
import com.workernotfound.job.domain.job.dto.response.FundingStatusResponse;
import com.workernotfound.job.domain.job.dto.response.MatchingSeatReservationCommandResponse;
import com.workernotfound.job.domain.job.dto.response.MatchingSeatReservationResponse;
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

    @Operation(
            summary = "매칭 모집 자리 예약",
            description = "공고가 OPEN 또는 MATCHING이고 근무 시작 전이며 남은 모집 자리가 있을 때 한 자리를 예약합니다. "
                    + "같은 Idempotency-Key의 동일 요청은 상태와 관계없이 발급 당시 스냅샷을 그대로 반환합니다. "
                    + "latitude/longitude는 저장된 공고의 예약 당시 좌표이며 출근 기준으로 전달됩니다. "
                    + "좌표 없는 기존 예약은 null 쌍을 유지합니다. lockedAmount는 1인 예정 급여(정수 KRW)입니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "예약 성공 또는 같은 키의 기존 예약",
                    content = @Content(schema = @Schema(implementation = MatchingSeatReservationResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 요청 또는 급여를 계산할 수 없는 공고", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "내부 인증 실패", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "공고 없음 (JOB-404-001)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "멱등 키 재사용(JOB-409-004), 매칭 불가 상태(JOB-409-005), 근무 시작(JOB-409-006), "
                            + "남은 자리 없음(JOB-409-007), 같은 매칭·지원 중복 점유(JOB-409-008)",
                    content = @Content
            )
    })
    ResponseEntity<ApiResponse<MatchingSeatReservationResponse>> reserveMatchingSeat(
            @Positive Long jobPostId,
            @NotBlank @Size(max = 100) @Pattern(regexp = "[!-~]+") String idempotencyKey,
            @Valid MatchingSeatReservationRequest request
    );

    @Operation(
            summary = "매칭 모집 자리 확정",
            description = "만료 전 RESERVED 예약을 CONSUMED로 바꿉니다. 이미 CONSUMED인 예약은 처음 확정한 같은 "
                    + "Idempotency-Key일 때만 성공하며, 원래 만료 시각이 지났어도 거절하지 않습니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "확정 성공 또는 같은 키의 재요청",
                    content = @Content(schema = @Schema(implementation = MatchingSeatReservationCommandResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "내부 인증 실패", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "공고 없음(JOB-404-001), 공고에 속한 예약 없음(JOB-404-003)",
                    content = @Content
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "다른 예약에 키 재사용(JOB-409-004), 마감·예치 차단 공고의 최초 확정(JOB-409-005), "
                            + "예약 만료(JOB-409-009), 반환된 예약 또는 다른 키로 이미 확정됨(JOB-409-010)",
                    content = @Content
            )
    })
    ResponseEntity<ApiResponse<MatchingSeatReservationCommandResponse>> confirmMatchingSeat(
            @Positive Long jobPostId,
            @Positive Long reservationId,
            @NotBlank @Size(max = 100) @Pattern(regexp = "[!-~]+") String idempotencyKey
    );

    @Operation(
            summary = "매칭 모집 자리 반환",
            description = "RESERVED 예약을 RELEASED로 바꿉니다. 이미 만료로 회수된 예약은 성공으로 처리하고, "
                    + "이미 반환된 예약은 처음 반환한 같은 Idempotency-Key일 때만 성공합니다. CONSUMED 예약은 반환하지 않습니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "반환 성공, 만료로 회수된 예약, 또는 같은 키의 재요청",
                    content = @Content(schema = @Schema(implementation = MatchingSeatReservationCommandResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "내부 인증 실패", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "공고 없음(JOB-404-001), 공고에 속한 예약 없음(JOB-404-003)",
                    content = @Content
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "다른 예약에 키 재사용(JOB-409-004), 확정된 예약 또는 다른 키로 이미 반환됨(JOB-409-010)",
                    content = @Content
            )
    })
    ResponseEntity<ApiResponse<MatchingSeatReservationCommandResponse>> releaseMatchingSeat(
            @Positive Long jobPostId,
            @Positive Long reservationId,
            @NotBlank @Size(max = 100) @Pattern(regexp = "[!-~]+") String idempotencyKey
    );

    @Operation(
            summary = "예치 상태 수신",
            description = "payment-service가 검증한 예치 상태를 공고에 연결된 주문의 저장 스냅샷과 대조해 반영합니다. "
                    + "funded=true이고 결제 대기 공고가 지원 마감·근무 시작 전이면 OPEN으로 공개하고, funded=false면 "
                    + "공고 상태는 두고 신규 지원 승인·자리 예약을 차단합니다. 같은 Idempotency-Key나 같은 주문·revision의 "
                    + "재전송은 처음 응답을 그대로 반환하며, 낮거나 같은 revision과 이전 주문 알림은 상태를 바꾸지 않고 200으로 기록합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "수신 기록 완료(result: PUBLISHED, FUNDING_CONFIRMED, PUBLICATION_SKIPPED, "
                            + "FUNDING_BLOCKED, STALE_REVISION, STALE_ORDER) 또는 같은 명령의 재요청",
                    content = @Content(schema = @Schema(implementation = FundingStatusResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "내부 인증 실패", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "공고 없음 (JOB-404-001)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "멱등 키 재사용(JOB-409-004), 주문·스냅샷 불일치(JOB-409-011), "
                            + "같은 주문·revision의 내용 충돌(JOB-409-012), 결제 주문 연결 대기(JOB-409-013, 같은 명령으로 재시도)",
                    content = @Content
            )
    })
    ResponseEntity<ApiResponse<FundingStatusResponse>> receiveFundingStatus(
            @Positive Long jobPostId,
            @NotBlank @Size(max = 100) @Pattern(regexp = "[!-~]+") String idempotencyKey,
            @Valid FundingStatusRequest request
    );
}
