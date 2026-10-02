package com.workernotfound.work.domain.work.controller;

import com.workernotfound.work.domain.work.controller.docs.WorkQueryControllerDocs;
import com.workernotfound.work.domain.work.dto.response.*;
import com.workernotfound.work.domain.work.service.WorkQueryService;
import com.workernotfound.work.global.response.ApiResponse;
import com.workernotfound.work.global.security.AuthenticatedMember;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/works/me")
public class WorkQueryController implements WorkQueryControllerDocs {
  private final WorkQueryService service;

  @GetMapping
  public ApiResponse<WorkPageResponse> getWorks(
      @AuthenticationPrincipal AuthenticatedMember member,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.success(service.getWorks(member, page, size));
  }

  @GetMapping("/{workId}")
  public ApiResponse<WorkResponse> getWork(
      @AuthenticationPrincipal AuthenticatedMember member, @PathVariable Long workId) {
    return ApiResponse.success(service.getWork(member, workId));
  }
}
