package com.workernotfound.work.domain.work.controller;

import com.workernotfound.work.domain.work.controller.docs.WorkAttendanceControllerDocs;
import com.workernotfound.work.domain.work.dto.request.CheckInRequest;
import com.workernotfound.work.domain.work.dto.response.*;
import com.workernotfound.work.domain.work.service.WorkAttendanceService;
import com.workernotfound.work.global.response.ApiResponse;
import com.workernotfound.work.global.security.AuthenticatedMember;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/works/me")
public class WorkAttendanceController implements WorkAttendanceControllerDocs {
  private final WorkAttendanceService service;

  @GetMapping("/attendance-policy")
  public ApiResponse<AttendancePolicyResponse> getPolicy() { return ApiResponse.success(service.getPolicy()); }

  @PostMapping("/{workId}/check-in")
  public ApiResponse<WorkResponse> checkIn(@AuthenticationPrincipal AuthenticatedMember member,
      @PathVariable Long workId, @RequestBody CheckInRequest request) {
    return ApiResponse.success(service.checkIn(member, workId, request));
  }

  @PostMapping("/{workId}/start")
  public ApiResponse<WorkResponse> start(@AuthenticationPrincipal AuthenticatedMember member,
      @PathVariable Long workId) { return ApiResponse.success(service.start(member, workId)); }

  @PostMapping("/{workId}/complete")
  public ApiResponse<WorkResponse> complete(@AuthenticationPrincipal AuthenticatedMember member,
      @PathVariable Long workId) { return ApiResponse.success(service.complete(member, workId)); }
}
