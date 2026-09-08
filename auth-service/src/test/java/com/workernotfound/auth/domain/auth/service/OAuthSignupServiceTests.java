package com.workernotfound.auth.domain.auth.service;

import com.workernotfound.auth.domain.account.entity.AuthAccount;
import com.workernotfound.auth.domain.account.entity.enums.MemberRole;
import com.workernotfound.auth.domain.account.entity.enums.OAuthProvider;
import com.workernotfound.auth.domain.account.entity.enums.SignupType;
import com.workernotfound.auth.domain.account.repository.AuthAccountRepository;
import com.workernotfound.auth.domain.account.repository.LocalCredentialRepository;
import com.workernotfound.auth.domain.account.repository.OAuthConnectionRepository;
import com.workernotfound.auth.domain.auth.dto.request.LocationRequest;
import com.workernotfound.auth.domain.auth.dto.request.OAuthOwnerSignupRequest;
import com.workernotfound.auth.domain.auth.dto.response.SignupResponse;
import com.workernotfound.auth.domain.auth.entity.enums.VerificationPurpose;
import com.workernotfound.auth.external.client.member.MemberServiceClient;
import com.workernotfound.auth.external.client.member.dto.CreateMemberResponse;
import com.workernotfound.auth.external.client.member.dto.MemberStatus;
import com.workernotfound.auth.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
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
class OAuthSignupServiceTests extends IntegrationTestSupport {

	private static final Duration VERIFIED_FLAG_TTL = Duration.ofMinutes(30);

	@Autowired
	private SignupService signupService;

	@Autowired
	private OAuthSignupTicketService oAuthSignupTicketService;

	@Autowired
	private AuthAccountRepository authAccountRepository;

	@Autowired
	private LocalCredentialRepository localCredentialRepository;

	@Autowired
	private OAuthConnectionRepository oAuthConnectionRepository;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@MockitoBean
	private MemberServiceClient memberServiceClient;

	@Test
	void oauthOwnerSignupSucceedsWithoutLocalCredential() {
		String email = "oauth-owner@example.com";
		String ticket = saveSignupTicket(email);
		saveSmsVerified("01022223333");
		when(memberServiceClient.createOwner(any())).thenReturn(new CreateMemberResponse(
			701L,
			email,
			"01022223333",
			com.workernotfound.auth.external.client.member.dto.MemberRole.OWNER,
			MemberStatus.ACTIVE
		));

		SignupResponse response = signupService.signupOAuthOwner(ownerSignupRequest(ticket));

		AuthAccount authAccount = authAccountRepository.findByEmail(email).orElseThrow();
		assertThat(response.memberId()).isEqualTo(701L);
		assertThat(response.email()).isEqualTo(email);
		assertThat(response.role()).isEqualTo(MemberRole.OWNER);
		assertThat(authAccount.getSignupType()).isEqualTo(SignupType.OAUTH);
		assertThat(localCredentialRepository.findByAuthAccount(authAccount)).isEmpty();
		assertThat(oAuthConnectionRepository.findByProviderAndProviderUserId(OAuthProvider.KAKAO, "kakao-owner"))
			.isPresent();
		verify(memberServiceClient).createOwner(argThat(memberRequest -> "일하는 카페".equals(memberRequest.storeName())));
	}

	@Test
	void oauthOwnerSignupFailsWhenSmsIsNotVerified() {
		String ticket = saveSignupTicket("oauth-owner-no-sms@example.com");

		assertThatThrownBy(() -> signupService.signupOAuthOwner(ownerSignupRequest(ticket)))
			.isInstanceOf(SignupException.class)
			.hasMessage("휴대폰 인증이 완료되지 않았습니다.");
		verify(memberServiceClient, never()).createOwner(any());
	}

	private String saveSignupTicket(String email) {
		return oAuthSignupTicketService.save(new OAuthSignupTicket(
			OAuthProvider.KAKAO,
			"kakao-owner",
			email,
			LocalDateTime.now()
		));
	}

	private OAuthOwnerSignupRequest ownerSignupRequest(String ticket) {
		return new OAuthOwnerSignupRequest(
			ticket,
			"오너",
			"01022223333",
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

	private void saveSmsVerified(String phoneNumber) {
		String key = "auth:verification:sms:verified:%s:%s".formatted(VerificationPurpose.SIGNUP, phoneNumber);
		redisTemplate.opsForValue().set(key, "true", VERIFIED_FLAG_TTL);
	}
}
