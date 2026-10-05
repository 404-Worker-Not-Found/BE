package com.workernotfound.auth.domain.account.service;

import com.workernotfound.auth.domain.account.dto.*;
import com.workernotfound.auth.domain.account.entity.*;
import com.workernotfound.auth.domain.account.entity.enums.*;
import com.workernotfound.auth.domain.account.repository.*;
import com.workernotfound.auth.domain.auth.service.VerificationService;
import com.workernotfound.auth.domain.auth.entity.enums.VerificationPurpose;
import com.workernotfound.auth.domain.token.service.*;
import com.workernotfound.auth.domain.token.repository.RefreshTokenRepository;
import com.workernotfound.auth.support.IntegrationTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Transactional
@TestPropertySource(properties = "auth.account-change.retry-delay-ms=3600000")
class ContactChangeTests extends IntegrationTestSupport {
    @Autowired ContactChangeTransactionService transactions;
    @Autowired AuthAccountRepository accounts;
    @Autowired ContactChangeRepository commands;
    @Autowired VerificationService verification;
    @Autowired TokenService tokenService;
    @Autowired RefreshTokenRepository tokens;

    @Test
    void codeIsConsumedOnceAndAllDeviceSessionsAreRevoked() {
        var account = account("contact");
        tokenService.issue(account, "device-a"); tokenService.issue(account, "device-b");
        doNothing().when(emailVerificationSender).send(anyString(), anyString());
        verification.sendEmailVerificationCode(VerificationPurpose.CONTACT_CHANGE, "new@example.com");
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(emailVerificationSender).send(eq("new@example.com"), captor.capture());
        String key = UUID.randomUUID().toString();
        var request = new ContactChangeRequest(ContactChangeRequest.Channel.EMAIL, "new@example.com", captor.getValue());
        var response = transactions.submit(claims(account), key, request);
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(account.getStatus()).isEqualTo(MemberStatus.UPDATING);
        assertThat(tokens.findAllByAuthAccountIdAndRevokedAtIsNull(account.getId())).isEmpty();
        assertThat(verification.consumeContactCode(true, request.target(), request.verificationCode())).isFalse();
        assertThat(transactions.submit(claims(account), key, request)).isEqualTo(response);
        transactions.complete(key, false);
        assertThat(account.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(account.getEmail()).isEqualTo("new@example.com");
        assertThat(commands.findById(key).orElseThrow().getTarget()).isNull();
    }
    @Test
    void signupProofCannotAuthorizeAContactChange() {
        var account = account("separate");
        verification.sendEmailVerificationCode(VerificationPurpose.SIGNUP, "separate-new@example.com");
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(emailVerificationSender).send(eq("separate-new@example.com"), captor.capture());
        assertThatThrownBy(() -> transactions.submit(claims(account), UUID.randomUUID().toString(),
                new ContactChangeRequest(ContactChangeRequest.Channel.EMAIL, "separate-new@example.com", captor.getValue())))
                .isInstanceOf(com.workernotfound.auth.global.exception.BusinessException.class);
        assertThat(account.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }
    @Test
    void inactiveAccountCannotIssueTokens() {
        var account = account("inactive"); account.withdraw();
        assertThatThrownBy(() -> tokenService.issue(account, "device"))
                .isInstanceOf(RefreshTokenException.class);
    }
    private AuthAccount account(String prefix) {
        return accounts.saveAndFlush(AuthAccount.builder().email(prefix + "@example.com").memberId(910L)
                .role(MemberRole.WORKER).signupType(SignupType.LOCAL).build());
    }
    private AuthTokenClaims claims(AuthAccount account) {
        return new AuthTokenClaims(account.getId(), account.getMemberId(), account.getRole());
    }
}
