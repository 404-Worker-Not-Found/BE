package com.workernotfound.work.global.security;

public record AuthenticatedMember(Long authAccountId, Long memberId, String role) {}
