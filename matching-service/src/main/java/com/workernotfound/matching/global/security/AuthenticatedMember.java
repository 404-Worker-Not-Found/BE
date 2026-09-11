package com.workernotfound.matching.global.security;

public record AuthenticatedMember(
	Long authAccountId,
	Long memberId,
	String role
) {
}
