package com.workernotfound.member.domain.member.controller.docs;

import com.workernotfound.member.domain.member.dto.response.OwnerBusinessResponse;
import com.workernotfound.member.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Member Internal")
public interface OwnerBusinessInternalControllerDocs {
    @Operation(summary = "활성 점주의 등록 사업장 조회", description = "X-Internal-Secret 필수. businessId는 ownerProfileId이며 등록번호·계좌 정보는 반환하지 않습니다.")
    ApiResponse<OwnerBusinessResponse> getBusiness(Long memberId);
}
