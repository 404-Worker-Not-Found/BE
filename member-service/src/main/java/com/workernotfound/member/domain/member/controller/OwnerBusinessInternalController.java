package com.workernotfound.member.domain.member.controller;

import com.workernotfound.member.domain.member.controller.docs.OwnerBusinessInternalControllerDocs;
import com.workernotfound.member.domain.member.dto.response.OwnerBusinessResponse;
import com.workernotfound.member.domain.owner.service.OwnerBusinessFindService;
import com.workernotfound.member.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/members/internal/owners")
public class OwnerBusinessInternalController implements OwnerBusinessInternalControllerDocs {
    private final OwnerBusinessFindService service;

    @Override
    @GetMapping("/{memberId}/business")
    public ApiResponse<OwnerBusinessResponse> getBusiness(@PathVariable Long memberId) {
        return ApiResponse.success(service.findBusiness(memberId));
    }
}
