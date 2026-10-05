package com.workernotfound.member.domain.member.controller.docs;

import com.workernotfound.member.domain.member.dto.request.UpdateMyMemberRequest;
import com.workernotfound.member.domain.member.dto.response.MyMemberResponse;
import com.workernotfound.member.global.config.OpenApiConfig;
import com.workernotfound.member.global.security.AuthenticatedMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Member", description = "회원 API")
public interface MemberControllerDocs {

    @Operation(summary = "내 정보 수정", description = "프로필 필드의 null/생략은 유지, 목록은 전체 교체합니다. 위치 객체를 전달하면 주소·좌표를 함께 교체하며 detailAddress의 null/생략은 상세주소를 지웁니다. 연락처·역할·사업자등록번호는 변경할 수 없습니다.",
        security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    ResponseEntity<com.workernotfound.member.global.response.ApiResponse<MyMemberResponse>> updateMyMember(
        @Parameter(hidden = true) AuthenticatedMember authenticatedMember,
        UpdateMyMemberRequest request);

	@Operation(
		summary = "내 회원 정보 조회",
		description = "인증된 사용자의 회원 기본 정보와 역할별 프로필을 조회합니다.",
		security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	)
	@ApiResponses({
		@ApiResponse(
			responseCode = "200",
			description = "내 회원 정보 조회 성공",
			content = @Content(schema = @Schema(implementation = MyMemberResponse.class))
		),
		@ApiResponse(responseCode = "401", description = "인증되지 않은 요청", content = @Content),
		@ApiResponse(responseCode = "404", description = "회원을 찾을 수 없음", content = @Content)
	})
	ResponseEntity<com.workernotfound.member.global.response.ApiResponse<MyMemberResponse>> getMyMember(
		@Parameter(hidden = true)
		AuthenticatedMember authenticatedMember
	);
}
