package com.workernotfound.auth.domain.auth.service;

import com.workernotfound.auth.domain.account.entity.AuthAccount;
import com.workernotfound.auth.domain.account.entity.LocalCredential;
import com.workernotfound.auth.domain.account.entity.enums.MemberStatus;
import com.workernotfound.auth.domain.account.repository.AuthAccountRepository;
import com.workernotfound.auth.domain.account.repository.LocalCredentialRepository;
import com.workernotfound.auth.domain.auth.dto.request.PasswordResetRequest;
import com.workernotfound.auth.domain.auth.entity.enums.VerificationPurpose;
import com.workernotfound.auth.domain.auth.exception.AuthErrorCode;
import com.workernotfound.auth.domain.token.repository.RefreshTokenRepository;
import com.workernotfound.auth.global.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

	private final AuthAccountRepository authAccountRepository;
	private final LocalCredentialRepository localCredentialRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final VerificationService verificationService;
	private final PasswordEncoder passwordEncoder;

	public void sendVerificationCode(String email) {
		AuthAccount account = authAccountRepository.findByEmail(email).orElse(null);
		if (account != null && account.getStatus() == MemberStatus.ACTIVE
				&& localCredentialRepository.findByAuthAccount(account).isPresent()) {
			verificationService.sendEmailVerificationCode(VerificationPurpose.PASSWORD_RESET, email);
		}
	}

	@Transactional
	public void resetPassword(PasswordResetRequest request) {
		validatePasswordLength(request.newPassword());
		AuthAccount account = authAccountRepository.findByEmailForUpdate(request.email())
				.orElseThrow(this::invalidReset);
		if (account.getStatus() != MemberStatus.ACTIVE) {
			throw invalidReset();
		}
		LocalCredential credential = localCredentialRepository.findByAuthAccount(account)
				.orElseThrow(this::invalidReset);
		String passwordHash = passwordEncoder.encode(request.newPassword());
		if (!verificationService.consumePasswordResetCode(request.email(), request.verificationCode())) {
			throw invalidReset();
		}
		LocalDateTime now = LocalDateTime.now();
		credential.changePassword(passwordHash, now);
		refreshTokenRepository.findAllByAuthAccountIdAndRevokedAtIsNull(account.getId())
				.forEach(token -> token.revoke(now));
	}

	private void validatePasswordLength(String password) {
		if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
			throw invalidReset();
		}
	}

	private BusinessException invalidReset() {
		return new BusinessException(AuthErrorCode.INVALID_PASSWORD_RESET);
	}
}
