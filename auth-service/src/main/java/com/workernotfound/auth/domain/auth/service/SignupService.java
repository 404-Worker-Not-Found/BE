package com.workernotfound.auth.domain.auth.service;

import com.workernotfound.auth.domain.account.entity.AuthAccount;
import com.workernotfound.auth.domain.account.entity.LocalCredential;
import com.workernotfound.auth.domain.account.entity.OAuthConnection;
import com.workernotfound.auth.domain.account.entity.enums.MemberRole;
import com.workernotfound.auth.domain.account.entity.enums.SignupType;
import com.workernotfound.auth.domain.account.repository.AuthAccountRepository;
import com.workernotfound.auth.domain.account.repository.LocalCredentialRepository;
import com.workernotfound.auth.domain.account.repository.OAuthConnectionRepository;
import com.workernotfound.auth.domain.auth.dto.request.LocationRequest;
import com.workernotfound.auth.domain.auth.dto.request.OAuthOwnerSignupRequest;
import com.workernotfound.auth.domain.auth.dto.request.OAuthWorkerSignupRequest;
import com.workernotfound.auth.domain.auth.dto.request.OwnerSignupRequest;
import com.workernotfound.auth.domain.auth.dto.request.WorkerSignupRequest;
import com.workernotfound.auth.domain.auth.dto.response.SignupResponse;
import com.workernotfound.auth.domain.auth.entity.enums.VerificationPurpose;
import com.workernotfound.auth.domain.token.dto.response.TokenResponse;
import com.workernotfound.auth.domain.token.service.TokenService;
import com.workernotfound.auth.external.client.member.MemberServiceClient;
import com.workernotfound.auth.external.client.member.dto.BusinessVerificationStatus;
import com.workernotfound.auth.external.client.member.dto.CreateMemberResponse;
import com.workernotfound.auth.external.client.member.dto.CreateOwnerMemberRequest;
import com.workernotfound.auth.external.client.member.dto.CreateWorkerMemberRequest;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class SignupService {

	private final AuthAccountRepository authAccountRepository;
	private final LocalCredentialRepository localCredentialRepository;
	private final OAuthConnectionRepository oAuthConnectionRepository;
	private final VerificationService verificationService;
	private final OAuthSignupTicketService oAuthSignupTicketService;
	private final MemberServiceClient memberServiceClient;
	private final PasswordEncoder passwordEncoder;
	private final TokenService tokenService;

	@Transactional
	public SignupResponse signupOwner(OwnerSignupRequest request) {
		validateSignupPrerequisites(request.email(), request.phoneNumber());
		CreateMemberResponse memberResponse = memberServiceClient.createOwner(toCreateOwnerMemberRequest(request));
		try {
			return saveLocalAccountAndIssueToken(
				memberResponse.memberId(),
				request.email(),
				MemberRole.OWNER,
				request.password(),
				request.deviceId()
			);
		} catch (RuntimeException exception) {
			compensateCreatedMember(memberResponse.memberId(), exception);
			throw exception;
		}
	}

	@Transactional
	public SignupResponse signupWorker(WorkerSignupRequest request) {
		validateSignupPrerequisites(request.email(), request.phoneNumber());
		CreateMemberResponse memberResponse = memberServiceClient.createWorker(toCreateWorkerMemberRequest(request));
		try {
			return saveLocalAccountAndIssueToken(
				memberResponse.memberId(),
				request.email(),
				MemberRole.WORKER,
				request.password(),
				request.deviceId()
			);
		} catch (RuntimeException exception) {
			compensateCreatedMember(memberResponse.memberId(), exception);
			throw exception;
		}
	}

	@Transactional
	public SignupResponse signupOAuthOwner(OAuthOwnerSignupRequest request) {
		OAuthSignupTicket signupTicket = oAuthSignupTicketService.getAndDelete(request.signupTicket());
		validateOAuthSignupPrerequisites(signupTicket, request.phoneNumber());
		CreateMemberResponse memberResponse = memberServiceClient.createOwner(toCreateOwnerMemberRequest(request, signupTicket));
		try {
			return saveOAuthAccountAndIssueToken(
				memberResponse.memberId(),
				signupTicket,
				MemberRole.OWNER,
				request.deviceId()
			);
		} catch (RuntimeException exception) {
			compensateCreatedMember(memberResponse.memberId(), exception);
			throw exception;
		}
	}

	@Transactional
	public SignupResponse signupOAuthWorker(OAuthWorkerSignupRequest request) {
		OAuthSignupTicket signupTicket = oAuthSignupTicketService.getAndDelete(request.signupTicket());
		validateOAuthSignupPrerequisites(signupTicket, request.phoneNumber());
		CreateMemberResponse memberResponse = memberServiceClient.createWorker(toCreateWorkerMemberRequest(request, signupTicket));
		try {
			return saveOAuthAccountAndIssueToken(
				memberResponse.memberId(),
				signupTicket,
				MemberRole.WORKER,
				request.deviceId()
			);
		} catch (RuntimeException exception) {
			compensateCreatedMember(memberResponse.memberId(), exception);
			throw exception;
		}
	}

	private void validateSignupPrerequisites(String email, String phoneNumber) {
		if (!verificationService.isEmailVerified(VerificationPurpose.SIGNUP, email)) {
			throw new SignupException("이메일 인증이 완료되지 않았습니다.");
		}
		if (!verificationService.isSmsVerified(VerificationPurpose.SIGNUP, phoneNumber)) {
			throw new SignupException("휴대폰 인증이 완료되지 않았습니다.");
		}
		if (authAccountRepository.existsByEmail(email)) {
			throw new SignupException("이미 가입된 이메일입니다.");
		}
	}

	private void validateOAuthSignupPrerequisites(OAuthSignupTicket signupTicket, String phoneNumber) {
		if (!verificationService.isSmsVerified(VerificationPurpose.SIGNUP, phoneNumber)) {
			throw new SignupException("휴대폰 인증이 완료되지 않았습니다.");
		}
		if (authAccountRepository.existsByEmail(signupTicket.providerEmail())) {
			throw new SignupException("이미 가입된 이메일입니다.");
		}
		if (oAuthConnectionRepository.existsByProviderAndProviderUserId(
			signupTicket.provider(),
			signupTicket.providerUserId()
		)) {
			throw new SignupException("이미 연결된 OAuth 계정입니다.");
		}
	}

	private SignupResponse saveLocalAccountAndIssueToken(
		Long memberId,
		String email,
		MemberRole role,
		String rawPassword,
		String deviceId
	) {
		AuthAccount authAccount = authAccountRepository.save(AuthAccount.builder()
			.memberId(memberId)
			.email(email)
			.role(role)
			.signupType(SignupType.LOCAL)
			.build());
		saveLocalCredential(authAccount, rawPassword);

		TokenResponse tokenResponse = tokenService.issue(authAccount, deviceId);
		return new SignupResponse(memberId, email, role, tokenResponse);
	}

	private SignupResponse saveOAuthAccountAndIssueToken(
		Long memberId,
		OAuthSignupTicket signupTicket,
		MemberRole role,
		String deviceId
	) {
		AuthAccount authAccount = authAccountRepository.save(AuthAccount.builder()
			.memberId(memberId)
			.email(signupTicket.providerEmail())
			.role(role)
			.signupType(SignupType.OAUTH)
			.build());
		oAuthConnectionRepository.save(OAuthConnection.builder()
			.authAccount(authAccount)
			.provider(signupTicket.provider())
			.providerUserId(signupTicket.providerUserId())
			.providerEmail(signupTicket.providerEmail())
			.connectedAt(LocalDateTime.now())
			.build());

		TokenResponse tokenResponse = tokenService.issue(authAccount, deviceId);
		return new SignupResponse(memberId, signupTicket.providerEmail(), role, tokenResponse);
	}

	private void saveLocalCredential(AuthAccount authAccount, String rawPassword) {
		LocalCredential localCredential = LocalCredential.builder()
			.authAccount(authAccount)
			.passwordHash(passwordEncoder.encode(rawPassword))
			.passwordChangedAt(LocalDateTime.now())
			.build();
		localCredentialRepository.save(localCredential);
	}

	private void compensateCreatedMember(Long memberId, RuntimeException originalException) {
		try {
			memberServiceClient.deleteMemberForSignupCompensation(memberId);
		} catch (RuntimeException compensationException) {
			log.warn(
				"member-service 회원가입 보상 삭제에 실패했습니다. memberId={}",
				memberId,
				compensationException
			);
			originalException.addSuppressed(compensationException);
		}
	}

	private CreateOwnerMemberRequest toCreateOwnerMemberRequest(OwnerSignupRequest request) {
		return new CreateOwnerMemberRequest(
			request.name(),
			request.email(),
			request.phoneNumber(),
			com.workernotfound.auth.external.client.member.dto.MemberRole.OWNER,
			request.businessRegistrationNumber(),
			request.businessType(),
			BusinessVerificationStatus.NOT_VERIFIED,
			toMemberLocationRequest(request.storeLocation())
		);
	}

	private CreateWorkerMemberRequest toCreateWorkerMemberRequest(WorkerSignupRequest request) {
		return new CreateWorkerMemberRequest(
			request.name(),
			request.email(),
			request.phoneNumber(),
			com.workernotfound.auth.external.client.member.dto.MemberRole.WORKER,
			request.desiredHourlyWage(),
			request.activityRadiusKm(),
			request.immediatelyAvailable(),
			toMemberLocationRequest(request.baseLocation()),
			request.preferredBusinessTypes(),
			toMemberAvailableTimeRequests(request)
		);
	}

	private com.workernotfound.auth.external.client.member.dto.LocationRequest toMemberLocationRequest(
		LocationRequest request
	) {
		return new com.workernotfound.auth.external.client.member.dto.LocationRequest(
			request.address(),
			request.detailAddress(),
			request.latitude(),
			request.longitude()
		);
	}

	private List<com.workernotfound.auth.external.client.member.dto.WorkerAvailableTimeRequest> toMemberAvailableTimeRequests(
		WorkerSignupRequest request
	) {
		return request.availableTimes().stream()
			.map(availableTime -> new com.workernotfound.auth.external.client.member.dto.WorkerAvailableTimeRequest(
				availableTime.dayOfWeek(),
				availableTime.startTime(),
				availableTime.endTime()
			))
			.toList();
	}

	private CreateOwnerMemberRequest toCreateOwnerMemberRequest(
		OAuthOwnerSignupRequest request,
		OAuthSignupTicket signupTicket
	) {
		return new CreateOwnerMemberRequest(
			request.name(),
			signupTicket.providerEmail(),
			request.phoneNumber(),
			com.workernotfound.auth.external.client.member.dto.MemberRole.OWNER,
			request.businessRegistrationNumber(),
			request.businessType(),
			BusinessVerificationStatus.NOT_VERIFIED,
			toMemberLocationRequest(request.storeLocation())
		);
	}

	private CreateWorkerMemberRequest toCreateWorkerMemberRequest(
		OAuthWorkerSignupRequest request,
		OAuthSignupTicket signupTicket
	) {
		return new CreateWorkerMemberRequest(
			request.name(),
			signupTicket.providerEmail(),
			request.phoneNumber(),
			com.workernotfound.auth.external.client.member.dto.MemberRole.WORKER,
			request.desiredHourlyWage(),
			request.activityRadiusKm(),
			request.immediatelyAvailable(),
			toMemberLocationRequest(request.baseLocation()),
			request.preferredBusinessTypes(),
			toMemberAvailableTimeRequests(request)
		);
	}

	private List<com.workernotfound.auth.external.client.member.dto.WorkerAvailableTimeRequest> toMemberAvailableTimeRequests(
		OAuthWorkerSignupRequest request
	) {
		return request.availableTimes().stream()
			.map(availableTime -> new com.workernotfound.auth.external.client.member.dto.WorkerAvailableTimeRequest(
				availableTime.dayOfWeek(),
				availableTime.startTime(),
				availableTime.endTime()
			))
			.toList();
	}
}
