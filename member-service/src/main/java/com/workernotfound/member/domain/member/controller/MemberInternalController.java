package com.workernotfound.member.domain.member.controller;

import com.workernotfound.member.domain.member.controller.docs.MemberInternalControllerDocs;
import com.workernotfound.member.domain.member.dto.request.CreateOwnerMemberRequest;
import com.workernotfound.member.domain.member.dto.request.CreateWorkerMemberRequest;
import com.workernotfound.member.domain.member.dto.response.CreateMemberResponse;
import com.workernotfound.member.domain.member.dto.response.MemberInternalResponse;
import com.workernotfound.member.domain.member.service.MemberApplicationService;
import com.workernotfound.member.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/members/internal")
public class MemberInternalController implements MemberInternalControllerDocs {

	private final MemberApplicationService memberApplicationService;

	@Override
	@PostMapping("/owners")
	public ResponseEntity<ApiResponse<CreateMemberResponse>> createOwnerMember(
		@Valid @RequestBody CreateOwnerMemberRequest request
	) {
		CreateMemberResponse response = memberApplicationService.createOwnerMember(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(HttpStatus.CREATED, response));
	}

	@Override
	@PostMapping("/workers")
	public ResponseEntity<ApiResponse<CreateMemberResponse>> createWorkerMember(
		@Valid @RequestBody CreateWorkerMemberRequest request
	) {
		CreateMemberResponse response = memberApplicationService.createWorkerMember(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(HttpStatus.CREATED, response));
	}

	@Override
	@GetMapping("/{memberId}")
	public ResponseEntity<ApiResponse<MemberInternalResponse>> getMemberInternal(
		@PathVariable Long memberId
	) {
		return ResponseEntity.ok(ApiResponse.success(memberApplicationService.getMemberInternal(memberId)));
	}
}
