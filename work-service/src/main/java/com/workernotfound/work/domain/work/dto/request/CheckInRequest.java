package com.workernotfound.work.domain.work.dto.request;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record CheckInRequest(
    @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
    @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude) {}
