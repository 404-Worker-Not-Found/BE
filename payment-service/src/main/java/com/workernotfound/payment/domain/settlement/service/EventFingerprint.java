package com.workernotfound.payment.domain.settlement.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 수신 기록에서 같은 eventId의 내용이 같은지 비교하는 SHA-256 지문. 각 값의 문자열 길이와 콜론을 앞에 붙여 경계를 고정한다.
 */
final class EventFingerprint {
  private EventFingerprint() {}

  static String of(Object... fields) {
    StringBuilder canonical = new StringBuilder();
    for (Object field : fields) {
      String value = String.valueOf(field);
      canonical.append(value.length()).append(':').append(value);
    }
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
    }
  }
}
