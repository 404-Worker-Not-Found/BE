package com.workernotfound.payment.domain.funding;

import com.workernotfound.payment.global.exception.*;
import com.workernotfound.payment.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments/internal/funding-incidents")
public class FundingIncidentController implements FundingIncidentControllerDocs {
  private final FundingIncidentApplicationService service;
  @GetMapping("/{id}")
  public ApiResponse<Map<String,Object>> getIncident(@PathVariable long id) {
    return ApiResponse.success(service.getIncident(id));
  }
}
interface FundingIncidentControllerDocs {
  @Operation(summary="예치 이상 기록 내부 조회",description="공유 시크릿 전용 읽기 계약. 자동 복구나 보류 해제 기능은 제공하지 않습니다.")
  ApiResponse<Map<String,Object>> getIncident(@Positive long id);
}
