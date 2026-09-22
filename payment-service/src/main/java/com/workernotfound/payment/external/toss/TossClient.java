package com.workernotfound.payment.external.toss;

import com.workernotfound.payment.domain.order.exception.OrderErrorCode;
import com.workernotfound.payment.global.exception.BusinessException;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class TossClient implements TossGateway {
  private final RestClient client;
  private final TossProperties properties;

  public TossClient(@Qualifier("tossRestClient") RestClient client, TossProperties properties) {
    this.client = client;
    this.properties = properties;
  }

  @Override
  public TossPayment confirm(String orderId, String paymentKey, BigDecimal amount) {
    requireEnabled();
    try {
      return requirePayment(
          client
              .post()
              .uri("/v1/payments/confirm")
              .header("Idempotency-Key", "confirm-" + orderId)
              .body(Map.of("orderId", orderId, "paymentKey", paymentKey, "amount", amount))
              .retrieve()
              .body(TossPayment.class));
    } catch (RestClientResponseException exception) {
      throw new TossClientException(exception.getStatusCode().value(), exception);
    } catch (RestClientException exception) {
      throw new TossClientException(null, exception);
    }
  }

  @Override
  public TossPayment getPayment(String paymentKey) {
    requireEnabled();
    try {
      return requirePayment(
          client.get().uri("/v1/payments/{key}", paymentKey).retrieve().body(TossPayment.class));
    } catch (RestClientResponseException exception) {
      throw new TossClientException(exception.getStatusCode().value(), exception);
    } catch (RestClientException exception) {
      throw new TossClientException(null, exception);
    }
  }

  private TossPayment requirePayment(TossPayment payment) {
    if (payment == null) throw unavailable();
    return payment;
  }

  private void requireEnabled() {
    if (!properties.enabled()) throw unavailable();
  }

  private BusinessException unavailable() {
    return new BusinessException(OrderErrorCode.PROVIDER_UNAVAILABLE);
  }
}
