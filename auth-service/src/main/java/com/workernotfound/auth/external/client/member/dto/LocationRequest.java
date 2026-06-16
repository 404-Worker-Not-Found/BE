package com.workernotfound.auth.external.client.member.dto;

import java.math.BigDecimal;

public record LocationRequest(
	String address,
	String detailAddress,
	BigDecimal latitude,
	BigDecimal longitude
) {
}
