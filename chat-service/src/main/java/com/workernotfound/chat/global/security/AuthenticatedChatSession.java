package com.workernotfound.chat.global.security;

import java.security.Principal;
import java.time.Instant;

public record AuthenticatedChatSession(AuthenticatedMember member, long expiresAt)
    implements Principal {
  @Override
  public String getName() { return member.role() + ":" + member.memberId(); }

  public boolean isExpired() { return Instant.now().getEpochSecond() >= expiresAt; }
}
