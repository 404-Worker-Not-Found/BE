package com.workernotfound.auth.domain.auth.service;

import com.workernotfound.auth.domain.account.entity.AuthAccount;
import com.workernotfound.auth.domain.account.entity.LocalCredential;
import com.workernotfound.auth.domain.account.entity.enums.MemberRole;
import com.workernotfound.auth.domain.account.entity.enums.SignupType;
import com.workernotfound.auth.domain.account.repository.AuthAccountRepository;
import com.workernotfound.auth.domain.account.repository.LocalCredentialRepository;
import com.workernotfound.auth.domain.auth.dto.request.LocationRequest;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SignupService {

	private final AuthAccountRepository authAccountRepository;
	private final LocalCredentialRepository localCredentialRepository;
	private final VerificationService verificationService;
	private final MemberServiceClient memberServiceClient;
	private final PasswordEncoder passwordEncoder;
	private final TokenService tokenService;

	@Transactional
	public SignupResponse signupOwner(OwnerSignupRequest request) {
		validateSignupPrerequisites(request.email(), request.phoneNumber());
		CreateMemberResponse memberResponse = memberServiceClient.createOwner(toCreateOwnerMemberRequest(request));
		return saveLocalAccountAndIssueToken(
			memberResponse.memberId(),
			request.email(),
			MemberRole.OWNER,
			request.password(),
			request.deviceId()
		);
	}

	@Transactional
	public SignupResponse signupWorker(WorkerSignupRequest request) {
		validateSignupPrerequisites(request.email(), request.phoneNumber());
		CreateMemberResponse memberResponse = memberServiceClient.createWorker(toCreateWorkerMemberRequest(request));
		return saveLocalAccountAndIssueToken(
			memberResponse.memberId(),
			request.email(),
			MemberRole.WORKER,
			request.password(),
			request.deviceId()
		);
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

	private SignupResponse saveLocalAccountAndIssueToken(
		Long memberId,
		String email,
		MemberRole role,
		String rawPassword,
		String deviceId
	) {
		// TODO: member-service 생성 성공 후 auth-service 저장 실패 시 고아 member 보상 처리를 추가한다.
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

	private void saveLocalCredential(AuthAccount authAccount, String rawPassword) {
		LocalCredential localCredential = LocalCredential.builder()
			.authAccount(authAccount)
			.passwordHash(passwordEncoder.encode(rawPassword))
			.passwordChangedAt(LocalDateTime.now())
			.build();
		localCredentialRepository.save(localCredential);
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
}
