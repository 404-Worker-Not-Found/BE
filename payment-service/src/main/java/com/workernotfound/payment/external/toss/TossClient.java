package com.workernotfound.payment.external.toss;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
  private static final ObjectMapper ERROR_READER = new ObjectMapper();
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

  // 금액은 정수 KRW로 보낸다. 부분 취소도 같은 API이며 cancelAmount를 빼면 전액 취소가 되므로 항상 보낸다.
  @Override
  public TossPayment cancel(String paymentKey, BigDecimal amount, String reason, String idempotencyKey) {
    requireEnabled();
    try {
      return requirePayment(
          client
              .post()
              .uri("/v1/payments/{key}/cancel", paymentKey)
              .header("Idempotency-Key", idempotencyKey)
              .body(Map.of("cancelReason", reason, "cancelAmount", amount.toBigIntegerExact()))
              .retrieve()
              .body(TossPayment.class));
    } catch (RestClientResponseException exception) {
      throw new TossClientException(
          exception.getStatusCode().value(), errorCode(exception), exception);
    } catch (RestClientException exception) {
      throw new TossClientException(null, exception);
    }
  }

  // 오류 본문에는 민감한 내용이 있을 수 있어 형식이 맞는 code만 꺼낸다. 해석하지 못하면 코드 없는 결과 미확정 오류로 둔다.
  private static String errorCode(RestClientResponseException exception) {
    try {
      JsonNode code = ERROR_READER.readTree(exception.getResponseBodyAsString()).path("code");
      return code.isTextual() && code.asText().matches("[A-Z0-9_]{1,64}") ? code.asText() : null;
    } catch (JsonProcessingException unreadableBody) {
      return null;
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
