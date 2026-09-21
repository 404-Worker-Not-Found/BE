package com.workernotfound.payment.external.job;

import com.workernotfound.payment.domain.order.exception.OrderErrorCode;
import com.workernotfound.payment.domain.order.repository.FundingNotificationRepository.Notification;
import com.workernotfound.payment.global.exception.BusinessException;
import com.workernotfound.payment.global.response.ApiResponse;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class FundingJobClient {
  private final RestClient client;

  public FundingJobClient(@Qualifier("fundingJobRestClient") RestClient client) {
    this.client = client;
  }

  public void deliver(Notification n) {
    ApiResponse<Void> result =
        client
            .post()
            .uri("/api/jobs/internal/{id}/funding-status", n.jobPostId())
            .header("Idempotency-Key", n.commandId())
            .body(
                Map.of(
                    "orderId",
                    n.orderId(),
                    "jobVersion",
                    n.jobVersion(),
                    "ownerMemberId",
                    n.ownerMemberId(),
                    "amount",
                    n.amount(),
                    "currency",
                    n.currency(),
                    "fundingRevision",
                    n.revision(),
                    "funded",
                    n.funded()))
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});
    if (result == null || !result.success())
      throw new BusinessException(OrderErrorCode.PROVIDER_UNAVAILABLE);
  }
}
