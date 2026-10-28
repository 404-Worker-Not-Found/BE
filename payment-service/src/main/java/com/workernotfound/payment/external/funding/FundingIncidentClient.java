package com.workernotfound.payment.external.funding;

import com.workernotfound.payment.domain.funding.FundingIncidentRepository.Delivery;
import com.workernotfound.payment.global.response.ApiResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class FundingIncidentClient {
  private final RestClient client;
  public FundingIncidentClient(@Qualifier("fundingIncidentRestClient") RestClient client) { this.client=client; }
  public record Request(Long jobPostId,Long memberId,String memberRole,Long matchingId,Long workId,java.time.LocalDateTime occurredAt) {}
  public void deliver(Delivery d) {
    ApiResponse<Void> response=client.post().uri("/api/notifications/internal/funding-incidents")
        .header("Idempotency-Key",d.commandId()).body(new Request(d.jobPostId(),d.memberId(),d.memberRole(),d.matchingId(),d.workId(),d.occurredAt()))
        .retrieve().body(new ParameterizedTypeReference<>(){});
    if(response==null || !response.success()) throw new IllegalStateException("예치 이상 알림 응답을 확인하지 못했습니다.");
  }
}
