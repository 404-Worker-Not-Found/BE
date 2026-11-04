package com.workernotfound.notification.domain.push.service;

import com.workernotfound.notification.domain.push.dto.request.DeviceTokenRequest;
import com.workernotfound.notification.domain.push.dto.response.DeviceTokenResponse;

import com.workernotfound.notification.global.account.AccountGateService;
import com.workernotfound.notification.global.security.AuthenticatedMember;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeviceTokenService {
  private final JdbcTemplate jdbc;
  private final AccountGateService gates;

  @Transactional
  public DeviceTokenResponse register(AuthenticatedMember member, DeviceTokenRequest request) {
    gates.requireActive(member.memberId());
    String hash = hash(request.token());
    jdbc.update("""
        INSERT INTO notification_device_tokens(token_hash,token,member_id,member_role,updated_at)
        VALUES(?,?,?,?,UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE
        generation=IF(member_id=VALUES(member_id) AND member_role=VALUES(member_role)
          AND active=TRUE,generation,generation+1),
        token=VALUES(token),member_id=VALUES(member_id),member_role=VALUES(member_role),active=TRUE,updated_at=UTC_TIMESTAMP(6)
        """, hash, request.token(), member.memberId(), member.role());
    Long id = jdbc.queryForObject("SELECT id FROM notification_device_tokens WHERE token_hash=?", Long.class, hash);
    return new DeviceTokenResponse(id);
  }

  @Transactional
  public void remove(AuthenticatedMember member, long id) {
    gates.requireActive(member.memberId());
    jdbc.update("""
        UPDATE notification_device_tokens SET active=FALSE,token='',generation=generation+1,updated_at=UTC_TIMESTAMP(6)
        WHERE id=? AND member_id=? AND member_role=? AND active=TRUE
        """, id, member.memberId(), member.role());
  }

  private String hash(String token) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
