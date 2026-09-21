package com.workernotfound.auth.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.workernotfound.auth.domain.account.entity.AuthAccount;
import com.workernotfound.auth.domain.account.entity.OAuthConnection;
import com.workernotfound.auth.domain.account.entity.enums.MemberRole;
import com.workernotfound.auth.domain.account.entity.enums.OAuthProvider;
import com.workernotfound.auth.domain.account.entity.enums.SignupType;
import com.workernotfound.auth.domain.account.repository.AuthAccountRepository;
import com.workernotfound.auth.domain.account.repository.OAuthConnectionRepository;
import com.workernotfound.auth.domain.auth.dto.request.LocationRequest;
import com.workernotfound.auth.domain.auth.dto.request.OAuthOwnerSignupRequest;
import com.workernotfound.auth.domain.auth.dto.request.OwnerSignupRequest;
import com.workernotfound.auth.domain.auth.exception.AuthErrorCode;
import com.workernotfound.auth.external.client.member.MemberServiceClient;
import com.workernotfound.auth.external.client.member.dto.CreateMemberResponse;
import com.workernotfound.auth.external.client.member.dto.MemberStatus;
import com.workernotfound.auth.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class SignupConstraintErrorTests extends IntegrationTestSupport {
	@Autowired private SignupService signupService;
	@MockitoSpyBean private AuthAccountRepository accounts;
	@MockitoSpyBean private OAuthConnectionRepository connections;
	@MockitoBean private VerificationService verificationService;
	@MockitoBean private OAuthSignupTicketService tickets;
	@MockitoBean private MemberServiceClient memberClient;

	@Test
	void mapsDatabaseEmailConflictAfterPrecheckAndCompensates() {
		String email = "constraint-email@example.com";
		AuthAccount existing = saveAccount(9801L, email);
		prepareSignup(9802L, email);
		doReturn(false).when(accounts).existsByEmail(email);

		assertThatThrownBy(() -> signupService.signupOwner(new OwnerSignupRequest(
			"오너", email, "password1234", "01077779999", "device", "1234567890", "카페", "CAFE", location())))
			.isInstanceOfSatisfying(SignupException.class, error -> {
				assertThat(error.getErrorCode()).isEqualTo(AuthErrorCode.EMAIL_ALREADY_EXISTS);
				assertThat(error.getCause()).isInstanceOf(DataIntegrityViolationException.class);
			});

		assertThat(accounts.findByEmail(email)).get().extracting(AuthAccount::getId).isEqualTo(existing.getId());
		assertThat(accounts.findByMemberId(9802L)).isEmpty();
		verify(memberClient).deleteMemberForSignupCompensation(9802L);
	}

	@Test
	void mapsDatabaseOAuthConflictAndRollsBackNewAccountBeforeCompensation() {
		AuthAccount existing = saveAccount(9803L, "constraint-original@example.com");
		connections.saveAndFlush(OAuthConnection.builder().authAccount(existing).provider(OAuthProvider.KAKAO)
			.providerUserId("constraint-provider-user").providerEmail(existing.getEmail()).connectedAt(LocalDateTime.now()).build());
		String email = "constraint-oauth@example.com";
		prepareSignup(9804L, email);
		when(tickets.getAndDelete("constraint-ticket")).thenReturn(new OAuthSignupTicket(
			OAuthProvider.KAKAO, "constraint-provider-user", email, LocalDateTime.now()));
		doReturn(false).when(connections).existsByProviderAndProviderUserId(OAuthProvider.KAKAO, "constraint-provider-user");
		org.mockito.Mockito.doAnswer(invocation -> {
			assertThat(accounts.findByMemberId(9804L)).isEmpty();
			return null;
		}).when(memberClient).deleteMemberForSignupCompensation(9804L);

		assertThatThrownBy(() -> signupService.signupOAuthOwner(new OAuthOwnerSignupRequest(
			"constraint-ticket", "오너", "01077779999", "device", "1234567890", "카페", "CAFE", location())))
			.isInstanceOfSatisfying(SignupException.class, error ->
				assertThat(error.getErrorCode()).isEqualTo(AuthErrorCode.OAUTH_ALREADY_CONNECTED));

		assertThat(accounts.findByEmail(email)).isEmpty();
		verify(memberClient).deleteMemberForSignupCompensation(9804L);
	}

	private AuthAccount saveAccount(Long memberId, String email) {
		return accounts.saveAndFlush(AuthAccount.builder().memberId(memberId).email(email)
			.role(MemberRole.OWNER).signupType(SignupType.OAUTH).build());
	}

	private void prepareSignup(Long memberId, String email) {
		when(verificationService.isEmailVerified(any(), any())).thenReturn(true);
		when(verificationService.isSmsVerified(any(), any())).thenReturn(true);
		when(memberClient.createOwner(any())).thenReturn(new CreateMemberResponse(memberId, email, "01077779999",
			com.workernotfound.auth.external.client.member.dto.MemberRole.OWNER, MemberStatus.ACTIVE));
	}

	private LocationRequest location() {
		return new LocationRequest("서울", "101호", BigDecimal.valueOf(37.5), BigDecimal.valueOf(127));
	}
}
