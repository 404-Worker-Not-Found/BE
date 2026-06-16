package com.workernotfound.auth.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record LocationRequest(
	@NotBlank
	@Size(max = 255)
	String address,

	@Size(max = 255)
	String detailAddress,

	@NotNull
	BigDecimal latitude,

	@NotNull
	BigDecimal longitude
) {
}
