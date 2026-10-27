package com.workernotfound.work.domain.work.controller;

import com.workernotfound.work.domain.work.controller.docs.WorkCancellationControllerDocs;
import com.workernotfound.work.domain.work.dto.request.CancellationRequest;
import com.workernotfound.work.domain.work.dto.response.WorkCancellationResponse;
import com.workernotfound.work.domain.work.service.WorkCancellationApplicationService;
import com.workernotfound.work.global.response.ApiResponse;
import com.workernotfound.work.global.security.AuthenticatedMember;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/works/me/{workId}/cancellations")
public class WorkCancellationController implements WorkCancellationControllerDocs {
  private final WorkCancellationApplicationService service;

  @PostMapping
  public ApiResponse<WorkCancellationResponse> request(@AuthenticationPrincipal AuthenticatedMember member,
      @PathVariable Long workId, @RequestHeader("Idempotency-Key") String key, @RequestBody CancellationRequest request) {
    return ApiResponse.success(service.request(member, workId, key, request));
  }

  @GetMapping
  public ApiResponse<List<WorkCancellationResponse>> getRequests(@AuthenticationPrincipal AuthenticatedMember member,
      @PathVariable Long workId) { return ApiResponse.success(service.getRequests(member, workId)); }

  @PostMapping("/{requestId}/approve")
  public ApiResponse<WorkCancellationResponse> approve(@AuthenticationPrincipal AuthenticatedMember member,
      @PathVariable Long workId, @PathVariable Long requestId) {
    return ApiResponse.success(service.respond(member, workId, requestId, true));
  }

  @PostMapping("/{requestId}/reject")
  public ApiResponse<WorkCancellationResponse> reject(@AuthenticationPrincipal AuthenticatedMember member,
      @PathVariable Long workId, @PathVariable Long requestId) {
    return ApiResponse.success(service.respond(member, workId, requestId, false));
  }

  @PostMapping("/{requestId}/withdraw")
  public ApiResponse<WorkCancellationResponse> withdraw(@AuthenticationPrincipal AuthenticatedMember member,
      @PathVariable Long workId, @PathVariable Long requestId) {
    return ApiResponse.success(service.withdraw(member, workId, requestId));
  }
}
