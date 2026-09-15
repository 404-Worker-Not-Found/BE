package com.workernotfound.matching.global.security;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JwtFailureBoundaryTests {
	static final String SECRET = "boundary-test-key-at-least-32-characters";

	JwtTokenParser parser() {
		var parser = new JwtTokenParser(new JwtProperties(SECRET));
		ReflectionTestUtils.invokeMethod(parser, "initialize");
		return parser;
	}

	@Test
	void malformedSignedClaimsAreAuthenticationFailures() throws Exception {
		var parser = parser();
		for (String payload :
				java.util.List.of(
						"{\"authAccountId\":1,\"memberId\":\"bad\",\"role\":\"OWNER\",\"exp\":9999999999}",
						"{\"authAccountId\":1,\"memberId\":2,\"role\":\"UNKNOWN\",\"exp\":9999999999}",
						"{\"authAccountId\":1,\"memberId\":2,\"role\":\"OWNER\",\"exp\":1}",
						"{\"authAccountId\":1,\"memberId\":2,\"role\":\"OWNER\"}")) {
			String token = token(payload);
			assertThat(parser.validateAccessToken(token)).isFalse();
			assertThatThrownBy(() -> parser.parseAccessToken(token))
					.isInstanceOf(InvalidAccessTokenException.class);
		}
		assertThat(parser.validateAccessToken(null)).isFalse();
		assertThat(parser.validateAccessToken("not-a-token")).isFalse();
	}

	@Test
	void brokenSigningEngineIsNotAnInvalidToken() throws Exception {
		var parser = parser();
		String token =
				token("{\"authAccountId\":1,\"memberId\":2,\"role\":\"OWNER\",\"exp\":9999999999}");
		assertThat(parser.validateAccessToken(token)).isTrue();
		ReflectionTestUtils.setField(parser, "secretKey", new byte[0]);
		assertThatThrownBy(() -> parser.validateAccessToken(token))
				.isInstanceOf(JwtProcessingException.class);
	}

	static String token(String payload) throws Exception {
		String unsigned = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}") + "." + encode(payload);
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return unsigned
				+ "."
				+ Base64.getUrlEncoder()
						.withoutPadding()
						.encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
	}

	static String encode(String value) {
		return Base64.getUrlEncoder()
				.withoutPadding()
				.encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}
}
