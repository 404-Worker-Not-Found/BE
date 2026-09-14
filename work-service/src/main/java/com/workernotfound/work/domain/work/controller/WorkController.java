package com.workernotfound.work.domain.work.controller;

import com.workernotfound.work.domain.work.controller.docs.WorkControllerDocs;
import com.workernotfound.work.domain.work.dto.request.ScheduledWorkRequest;
import com.workernotfound.work.domain.work.dto.response.ScheduledWorkResponse;
import com.workernotfound.work.domain.work.service.WorkApplicationService;
import com.workernotfound.work.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/works/internal")
public class WorkController implements WorkControllerDocs {
  private final WorkApplicationService service;

  @PostMapping("/scheduled")
  public ApiResponse<ScheduledWorkResponse> create(
      @RequestHeader("Idempotency-Key") String key, @RequestBody ScheduledWorkRequest request) {
    return ApiResponse.success(service.create(key, request));
  }

  @PostMapping("/{workId}/cancel")
  public ApiResponse<Void> cancel(
      @RequestHeader("Idempotency-Key") String key, @PathVariable Long workId) {
    service.cancel(key, workId);
    return ApiResponse.success(null);
  }
}
