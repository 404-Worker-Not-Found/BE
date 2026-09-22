package com.workernotfound.payment.domain.order.controller;

import com.workernotfound.payment.domain.order.controller.docs.OrderControllerDocs;
import com.workernotfound.payment.domain.order.dto.*;
import com.workernotfound.payment.domain.order.service.OrderApplicationService;
import com.workernotfound.payment.global.response.ApiResponse;
import com.workernotfound.payment.global.security.AuthenticatedMember;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments")
public class OrderController implements OrderControllerDocs {
  private final OrderApplicationService service;

  @PostMapping("/internal/orders")
  public ApiResponse<OrderResponse> create(
      @RequestHeader("Idempotency-Key") String key, @RequestBody CreateOrderRequest request) {
    return ApiResponse.success(service.create(key, request));
  }

  @GetMapping("/orders/{orderId}")
  public ApiResponse<OrderResponse> getOrder(
      @PathVariable String orderId, @AuthenticationPrincipal AuthenticatedMember member) {
    return ApiResponse.success(service.getOrder(orderId, member.memberId()));
  }

  @PostMapping("/orders/{orderId}/confirm")
  public ApiResponse<OrderResponse> confirm(
      @PathVariable String orderId,
      @AuthenticationPrincipal AuthenticatedMember member,
      @RequestBody ConfirmOrderRequest request) {
    return ApiResponse.success(service.confirm(orderId, member.memberId(), request));
  }

  @PostMapping("/webhooks/toss")
  public ApiResponse<Void> webhook(@RequestBody TossWebhook event) {
    service.receiveWebhook(event);
    return ApiResponse.success(null);
  }
}
