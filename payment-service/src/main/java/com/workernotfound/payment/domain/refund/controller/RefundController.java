package com.workernotfound.payment.domain.refund.controller;

import com.workernotfound.payment.domain.refund.controller.docs.RefundControllerDocs;
import com.workernotfound.payment.domain.refund.dto.request.RefundRequest;
import com.workernotfound.payment.domain.refund.dto.response.RefundResponse;
import com.workernotfound.payment.domain.refund.dto.response.RefundReviewPageResponse;
import com.workernotfound.payment.domain.refund.service.RefundApplicationService;
import com.workernotfound.payment.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments/internal/refunds")
public class RefundController implements RefundControllerDocs {
  private final RefundApplicationService service;

  @PostMapping
  public ApiResponse<RefundResponse> request(
      @RequestHeader("Idempotency-Key") String key, @RequestBody RefundRequest request) {
    return ApiResponse.success(service.request(key, request));
  }

  @GetMapping("/{refundId}")
  public ApiResponse<RefundResponse> getRefund(@PathVariable Long refundId) {
    return ApiResponse.success(service.getRefund(refundId));
  }

  @GetMapping("/reviews")
  public ApiResponse<RefundReviewPageResponse> getReviews(
      @RequestParam(defaultValue = "0") Long afterRefundId, @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.success(service.getReviews(afterRefundId, size));
  }
}
