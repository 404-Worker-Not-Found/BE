package com.workernotfound.payment.domain.payment.controller;

import com.workernotfound.payment.domain.payment.controller.docs.PaymentControllerDocs;
import com.workernotfound.payment.domain.payment.dto.request.PaymentLockRequest;
import com.workernotfound.payment.domain.payment.dto.response.PaymentLockResponse;
import com.workernotfound.payment.domain.payment.service.PaymentApplicationService;
import com.workernotfound.payment.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments/internal/locks")
public class PaymentController implements PaymentControllerDocs {
  private final PaymentApplicationService service;

  @PostMapping
  public ApiResponse<PaymentLockResponse> lock(
      @RequestHeader("Idempotency-Key") String key, @RequestBody PaymentLockRequest request) {
    return ApiResponse.success(service.lock(key, request));
  }

  @PostMapping("/{paymentId}/release")
  public ApiResponse<Void> release(
      @RequestHeader("Idempotency-Key") String key, @PathVariable Long paymentId) {
    service.release(key, paymentId);
    return ApiResponse.success(null);
  }
}
