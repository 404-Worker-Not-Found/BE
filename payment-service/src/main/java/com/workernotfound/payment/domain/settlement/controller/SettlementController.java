package com.workernotfound.payment.domain.settlement.controller;

import com.workernotfound.payment.domain.settlement.controller.docs.SettlementControllerDocs;
import com.workernotfound.payment.domain.settlement.dto.SettlementResponse;
import com.workernotfound.payment.domain.settlement.service.SettlementApplicationService;
import com.workernotfound.payment.global.response.ApiResponse;
import com.workernotfound.payment.global.security.AuthenticatedMember;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments/me/settlements")
public class SettlementController implements SettlementControllerDocs {
  private final SettlementApplicationService service;

  @GetMapping
  public ApiResponse<List<SettlementResponse>> getSettlements(@AuthenticationPrincipal AuthenticatedMember member,
      @RequestParam(required = false) Long beforeId, @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.success(service.getSettlements(member, beforeId, size));
  }

  @GetMapping("/{id}")
  public ApiResponse<SettlementResponse> getSettlement(@AuthenticationPrincipal AuthenticatedMember member,
      @PathVariable long id) {
    return ApiResponse.success(service.getSettlement(member, id));
  }
}
