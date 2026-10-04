package com.workernotfound.job.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 서비스 간 내부 API 인증값.
 *
 * <p>이 값은 받는 요청의 {@code X-Internal-Secret}과 비교하고, 보내는 요청의 같은 헤더에 그대로 실린다. HTTP 헤더로
 * 보낼 수 없거나(줄바꿈 등 제어 문자) 받는 쪽에서 앞뒤 공백 제거·문자 인코딩 차이로 비교가 어긋날 수 있는 값은 시작 시
 * 거절한다. 값을 다듬어 보정하지 않으며, 오류 메시지와 {@link #toString()}에 값을 포함하지 않는다.
 */
@ConfigurationProperties(prefix = "job.internal")
public record InternalApiProperties(
	String secret
) {

	private static final char FIRST_VISIBLE_ASCII = '!';
	private static final char LAST_VISIBLE_ASCII = '~';

	public InternalApiProperties {
		if (!isHeaderSafe(secret)) {
			throw new IllegalArgumentException(
				"job.internal.secret은 비어 있지 않아야 하며 공백·제어 문자 없이 출력 가능한 ASCII 문자(0x21~0x7E)로만 구성해야 합니다.");
		}
	}

	private static boolean isHeaderSafe(String value) {
		if (value == null || value.isEmpty()) {
			return false;
		}
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character < FIRST_VISIBLE_ASCII || character > LAST_VISIBLE_ASCII) {
				return false;
			}
		}
		return true;
	}

	@Override
	public String toString() {
		return "InternalApiProperties[secret=****]";
	}
}
