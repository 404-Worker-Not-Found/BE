package com.workernotfound.auth.domain.auth.service;

import com.workernotfound.auth.domain.account.entity.enums.MemberRole;
import com.workernotfound.auth.domain.account.entity.enums.OAuthProvider;
import com.workernotfound.auth.domain.auth.dto.request.LocationRequest;
import com.workernotfound.auth.domain.auth.dto.request.OAuthOwnerSignupRequest;
import com.workernotfound.auth.domain.auth.dto.request.OwnerSignupRequest;
import com.workernotfound.auth.domain.auth.entity.enums.VerificationPurpose;
import com.workernotfound.auth.external.client.member.MemberServiceClient;
import com.workernotfound.auth.external.client.member.dto.CreateMemberResponse;
import com.workernotfound.auth.external.client.member.dto.MemberStatus;
import com.workernotfound.auth.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SignupTransactionBoundaryTests extends IntegrationTestSupport {

	private static final Duration VERIFIED_FLAG_TTL = Duration.ofMinutes(30);
	private static final String OWNER_EMAIL = "transaction-owner@example.com";
	private static final String OAUTH_OWNER_EMAIL = "transaction-oauth-owner@example.com";
	private static final String OWNER_PHONE_NUMBER = "01077778888";

	@Autowired
	private SignupService signupService;

	@Autowired
	private OAuthSignupTicketService oAuthSignupTicketService;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@MockitoBean
	private MemberServiceClient memberServiceClient;

	@MockitoBean
	private SignupPersistenceService signupPersistenceService;

	@AfterEach
	void deleteVerificationFlags() {
		redisTemplate.delete(emailVerificationKey(OWNER_EMAIL));
		redisTemplate.delete(smsVerificationKey(OWNER_PHONE_NUMBER));
	}

	@Test
	void callsOwnerMemberServiceOutsideAuthTransactionAndCompensatesPersistenceFailure() {
		saveEmailVerified(OWNER_EMAIL);
		saveSmsVerified(OWNER_PHONE_NUMBER);
		when(memberServiceClient.createOwner(any())).thenAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return ownerMemberResponse(801L, OWNER_EMAIL);
		});
		when(signupPersistenceService.saveLocalAccountAndIssueToken(any(), any(), any(), any(), any()))
			.thenThrow(new SignupException("auth 계정 저장에 실패했습니다."));

		assertThatThrownBy(() -> signupService.signupOwner(ownerSignupRequest()))
			.isInstanceOf(SignupException.class);

		verify(memberServiceClient).deleteMemberForSignupCompensation(801L);
	}

	@Test
	void callsOAuthOwnerMemberServiceOutsideAuthTransaction() {
		String ticket = oAuthSignupTicketService.save(new OAuthSignupTicket(
			OAuthProvider.KAKAO,
			"transaction-kakao-owner",
			OAUTH_OWNER_EMAIL,
			LocalDateTime.now()
		));
		saveSmsVerified(OWNER_PHONE_NUMBER);
		when(memberServiceClient.createOwner(any())).thenAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return ownerMemberResponse(802L, OAUTH_OWNER_EMAIL);
		});

		signupService.signupOAuthOwner(oAuthOwnerSignupRequest(ticket));

		verify(signupPersistenceService).saveOAuthAccountAndIssueToken(
			eq(802L),
			argThat(signupTicket ->
				OAuthProvider.KAKAO == signupTicket.provider()
					&& "transaction-kakao-owner".equals(signupTicket.providerUserId())
					&& OAUTH_OWNER_EMAIL.equals(signupTicket.providerEmail())
			),
			eq(MemberRole.OWNER),
			eq("device-1")
		);
	}

	private OwnerSignupRequest ownerSignupRequest() {
		return new OwnerSignupRequest(
			"오너",
			OWNER_EMAIL,
			"password1234",
			OWNER_PHONE_NUMBER,
			"device-1",
			"1234567890",
			"일하는 카페",
			"CAFE",
			locationRequest()
		);
	}

	private OAuthOwnerSignupRequest oAuthOwnerSignupRequest(String ticket) {
		return new OAuthOwnerSignupRequest(
			ticket,
			"오너",
			OWNER_PHONE_NUMBER,
			"device-1",
			"1234567890",
			"일하는 카페",
			"CAFE",
			locationRequest()
		);
	}

	private LocationRequest locationRequest() {
		return new LocationRequest(
			"서울시 강남구 테헤란로 1",
			"101호",
			BigDecimal.valueOf(37.4979),
			BigDecimal.valueOf(127.0276)
		);
	}

	private CreateMemberResponse ownerMemberResponse(Long memberId, String email) {
		return new CreateMemberResponse(
			memberId,
			email,
			OWNER_PHONE_NUMBER,
			com.workernotfound.auth.external.client.member.dto.MemberRole.OWNER,
			MemberStatus.ACTIVE
		);
	}

	private void saveEmailVerified(String email) {
		redisTemplate.opsForValue().set(emailVerificationKey(email), "true", VERIFIED_FLAG_TTL);
	}

	private void saveSmsVerified(String phoneNumber) {
		redisTemplate.opsForValue().set(smsVerificationKey(phoneNumber), "true", VERIFIED_FLAG_TTL);
	}

	private String emailVerificationKey(String email) {
		return "auth:verification:email:verified:%s:%s".formatted(VerificationPurpose.SIGNUP, email);
	}

	private String smsVerificationKey(String phoneNumber) {
		return "auth:verification:sms:verified:%s:%s".formatted(VerificationPurpose.SIGNUP, phoneNumber);
	}
}
