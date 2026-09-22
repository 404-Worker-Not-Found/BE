package com.workernotfound.payment.domain.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TossWebhook(@NotBlank @Size(max = 100) String eventType, @NotNull @Valid Data data) {
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Data(
      @NotBlank @Size(max = 64) String orderId, @NotBlank @Size(max = 200) String paymentKey) {}
}
