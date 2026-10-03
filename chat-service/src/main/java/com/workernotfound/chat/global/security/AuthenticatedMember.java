package com.workernotfound.chat.global.security;

public record AuthenticatedMember(Long authAccountId, Long memberId, String role) {}
