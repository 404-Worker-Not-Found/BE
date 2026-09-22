package com.workernotfound.payment.global.security;

public record AuthenticatedMember(
	Long authAccountId,
	Long memberId,
	String role
) {
}
