package com.workernotfound.job.global.security;

public record MemberClaims(Long authAccountId, Long memberId, String role) {
}
