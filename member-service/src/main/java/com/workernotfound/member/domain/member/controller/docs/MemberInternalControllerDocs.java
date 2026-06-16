package com.workernotfound.member.domain.member.controller.docs;

import com.workernotfound.member.domain.member.dto.request.CreateOwnerMemberRequest;
import com.workernotfound.member.domain.member.dto.request.CreateWorkerMemberRequest;
import com.workernotfound.member.domain.member.dto.response.CreateMemberResponse;
import com.workernotfound.member.domain.member.dto.response.MemberInternalResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Member Internal", description = "서비스 간 회원 내부 API")
public interface MemberInternalControllerDocs {

	@Operation(summary = "OWNER 회원 생성", description = "auth-service에서 OWNER 회원과 사업자 프로필 생성을 요청합니다.")
	@ApiResponses({
		@ApiResponse(
			responseCode = "201",
			description = "OWNER 회원 생성 성공",
			content = @Content(schema = @Schema(implementation = CreateMemberResponse.class))
		),
		@ApiResponse(responseCode = "400", description = "잘못된 회원 생성 요청", content = @Content),
		@ApiResponse(responseCode = "409", description = "이메일 또는 휴대폰 번호 중복", content = @Content)
	})
	ResponseEntity<com.workernotfound.member.global.response.ApiResponse<CreateMemberResponse>> createOwnerMember(
		@RequestBody(
			description = "OWNER 회원 생성 요청",
			required = true,
			content = @Content(schema = @Schema(implementation = CreateOwnerMemberRequest.class))
		)
		CreateOwnerMemberRequest request
	);

	@Operation(summary = "WORKER 회원 생성", description = "auth-service에서 WORKER 회원과 근로자 프로필 생성을 요청합니다.")
	@ApiResponses({
		@ApiResponse(
			responseCode = "201",
			description = "WORKER 회원 생성 성공",
			content = @Content(schema = @Schema(implementation = CreateMemberResponse.class))
		),
		@ApiResponse(responseCode = "400", description = "잘못된 회원 생성 요청", content = @Content),
		@ApiResponse(responseCode = "409", description = "이메일 또는 휴대폰 번호 중복", content = @Content)
	})
	ResponseEntity<com.workernotfound.member.global.response.ApiResponse<CreateMemberResponse>> createWorkerMember(
		@RequestBody(
			description = "WORKER 회원 생성 요청",
			required = true,
			content = @Content(schema = @Schema(implementation = CreateWorkerMemberRequest.class))
		)
		CreateWorkerMemberRequest request
	);

	@Operation(summary = "내부 회원 기본 정보 조회", description = "다른 서비스에서 회원 기본 정보를 조회합니다.")
	@ApiResponses({
		@ApiResponse(
			responseCode = "200",
			description = "회원 기본 정보 조회 성공",
			content = @Content(schema = @Schema(implementation = MemberInternalResponse.class))
		),
		@ApiResponse(responseCode = "404", description = "회원을 찾을 수 없음", content = @Content)
	})
	ResponseEntity<com.workernotfound.member.global.response.ApiResponse<MemberInternalResponse>> getMemberInternal(
		@Parameter(description = "회원 ID", required = true)
		Long memberId
	);

	@Operation(summary = "회원가입 보상용 회원 삭제", description = "auth-service 회원가입 실패 보상 처리로 생성된 회원을 삭제합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "회원 삭제 성공", content = @Content),
		@ApiResponse(responseCode = "404", description = "회원을 찾을 수 없음", content = @Content)
	})
	ResponseEntity<com.workernotfound.member.global.response.ApiResponse<Void>> deleteMemberForSignupCompensation(
		@Parameter(description = "회원 ID", required = true)
		Long memberId
	);
}
