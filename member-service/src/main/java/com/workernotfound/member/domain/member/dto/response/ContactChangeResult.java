package com.workernotfound.member.domain.member.dto.response;

public record ContactChangeResult(String commandId, Long memberId, String channel, String target, boolean accepted) {}
