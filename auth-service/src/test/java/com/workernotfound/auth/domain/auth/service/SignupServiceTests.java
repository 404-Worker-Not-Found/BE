package com.workernotfound.auth.domain.auth.service;

import com.workernotfound.auth.domain.account.entity.enums.MemberRole;
import com.workernotfound.auth.domain.account.repository.AuthAccountRepository;
import com.workernotfound.auth.domain.auth.dto.request.LocationRequest;
import com.workernotfound.auth.domain.auth.dto.request.OwnerSignupRequest;
import com.workernotfound.auth.domain.auth.dto.response.SignupResponse;
import com.workernotfound.auth.domain.auth.entity.enums.VerificationPurpose;
import com.workernotfound.auth.external.client.member.MemberServiceClient;
import com.workernotfound.auth.external.client.member.dto.CreateMemberResponse;
import com.workernotfound.auth.external.client.member.dto.MemberStatus;
import com.workernotfound.auth.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Transactional
class SignupServiceTests extends IntegrationTestSupport {

	private static final Duration VERIFIED_FLAG_TTL = Duration.ofMinutes(30);

	@Autowired
	private SignupService signupService;

	@Autowired
	private AuthAccountRepository authAccountRepository;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@MockitoBean
	private MemberServiceClient memberServiceClient;

	@Test
	void signupOwnerFailsWhenEmailIsNotVerified() {
		OwnerSignupRequest request = ownerSignupRequest("email-not-verified@example.com", "01011112222");
		saveSmsVerified(request.phoneNumber());

		assertThatThrownBy(() -> signupService.signupOwner(request))
			.isInstanceOf(SignupException.class)
			.hasMessage("이메일 인증이 완료되지 않았습니다.");
		verify(memberServiceClient, never()).createOwner(any());
	}

	@Test
	void signupOwnerFailsWhenSmsIsNotVerified() {
		OwnerSignupRequest request = ownerSignupRequest("sms-not-verified@example.com", "01011113333");
		saveEmailVerified(request.email());

		assertThatThrownBy(() -> signupService.signupOwner(request))
			.isInstanceOf(SignupException.class)
			.hasMessage("휴대폰 인증이 완료되지 않았습니다.");
		verify(memberServiceClient, never()).createOwner(any());
	}

	@Test
	void signupOwnerSucceedsWhenEmailAndSmsAreVerified() {
		OwnerSignupRequest request = ownerSignupRequest("signup-success@example.com", "01011114444");
		saveEmailVerified(request.email());
		saveSmsVerified(request.phoneNumber());
		when(memberServiceClient.createOwner(any())).thenReturn(new CreateMemberResponse(
			501L,
			request.email(),
			request.phoneNumber(),
			com.workernotfound.auth.external.client.member.dto.MemberRole.OWNER,
			MemberStatus.ACTIVE
		));

		SignupResponse response = signupService.signupOwner(request);

		assertThat(response.memberId()).isEqualTo(501L);
		assertThat(response.email()).isEqualTo(request.email());
		assertThat(response.role()).isEqualTo(MemberRole.OWNER);
		assertThat(response.tokenResponse().accessToken()).isNotBlank();
		assertThat(response.tokenResponse().refreshToken()).isNotBlank();
		assertThat(authAccountRepository.existsByEmail(request.email())).isTrue();
		verify(memberServiceClient).createOwner(argThat(memberRequest -> "일하는 카페".equals(memberRequest.storeName())));
	}

	private OwnerSignupRequest ownerSignupRequest(String email, String phoneNumber) {
		return new OwnerSignupRequest(
			"오너",
			email,
			"password1234",
			phoneNumber,
			"device-1",
			"1234567890",
			"일하는 카페",
			"CAFE",
			new LocationRequest(
				"서울시 강남구 테헤란로 1",
				"101호",
				BigDecimal.valueOf(37.4979),
				BigDecimal.valueOf(127.0276)
			)
		);
	}

	private void saveEmailVerified(String email) {
		String key = "auth:verification:email:verified:%s:%s".formatted(VerificationPurpose.SIGNUP, email);
		redisTemplate.opsForValue().set(key, "true", VERIFIED_FLAG_TTL);
	}

	private void saveSmsVerified(String phoneNumber) {
		String key = "auth:verification:sms:verified:%s:%s".formatted(VerificationPurpose.SIGNUP, phoneNumber);
		redisTemplate.opsForValue().set(key, "true", VERIFIED_FLAG_TTL);
	}
}
