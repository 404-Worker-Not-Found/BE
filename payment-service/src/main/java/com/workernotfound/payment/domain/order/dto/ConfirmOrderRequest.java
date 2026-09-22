package com.workernotfound.payment.domain.order.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record ConfirmOrderRequest(
    @NotBlank @Size(max = 200) @Pattern(regexp = "[A-Za-z0-9_-]+") String paymentKey,
    @NotNull @DecimalMin("100") @Digits(integer = 17, fraction = 0) BigDecimal amount) {}
