package com.workernotfound.member.domain.member.controller;

import com.workernotfound.member.domain.member.controller.docs.MemberControllerDocs;
import com.workernotfound.member.domain.member.dto.response.MyMemberResponse;
import com.workernotfound.member.domain.member.service.MemberApplicationService;
import com.workernotfound.member.global.response.ApiResponse;
import com.workernotfound.member.global.security.AuthenticatedMember;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/members")
public class MemberController implements MemberControllerDocs {

	private final MemberApplicationService memberApplicationService;

	@Override
	@GetMapping("/me")
	public ResponseEntity<ApiResponse<MyMemberResponse>> getMyMember(
		@AuthenticationPrincipal AuthenticatedMember authenticatedMember
	) {
		return ResponseEntity.ok(ApiResponse.success(
			memberApplicationService.getMyMember(authenticatedMember.memberId())
		));
	}
}
