package com.workernotfound.notification.domain.notification.controller;

import com.workernotfound.notification.domain.notification.service.FundingIncidentService;
import com.workernotfound.notification.domain.notification.service.FundingIncidentService.Command;
import com.workernotfound.notification.global.exception.*;
import com.workernotfound.notification.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications/internal/funding-incidents")
public class FundingIncidentController implements FundingIncidentControllerDocs {
  private final FundingIncidentService service;
  @Value("${notification.internal.secret:}") private String internalSecret;
  @PostMapping
  public ApiResponse<Void> receive(@RequestHeader(value="X-Internal-Secret",required=false) String secret,
      @RequestHeader("Idempotency-Key") String key,@RequestBody Command command) {
    if(internalSecret.isBlank() || secret==null || !MessageDigest.isEqual(internalSecret.getBytes(StandardCharsets.UTF_8),secret.getBytes(StandardCharsets.UTF_8)))
      throw new BusinessException(GlobalErrorCode.UNAUTHORIZED);
    service.receive(key,command);return ApiResponse.success(null);
  }
}
interface FundingIncidentControllerDocs {
  @Operation(summary="예치 이상 인앱 알림 내부 수신",description="공유 시크릿과 불변 명령 ID를 검증합니다. 중복은 알림을 추가하지 않습니다.")
  ApiResponse<Void> receive(String secret,@NotBlank @Size(max=110) @Pattern(regexp="[!-~]+") String key,@Valid Command command);
}
