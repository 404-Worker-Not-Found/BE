package com.workernotfound.member.domain.member.dto.response;

import java.math.BigDecimal;

public record LocationResponse(
	Long locationId,
	String address,
	String detailAddress,
	BigDecimal latitude,
	BigDecimal longitude
) {
}
