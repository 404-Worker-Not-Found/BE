package com.workernotfound.notification.global.security;

public record AuthenticatedMember(Long authAccountId, Long memberId, String role) {}
