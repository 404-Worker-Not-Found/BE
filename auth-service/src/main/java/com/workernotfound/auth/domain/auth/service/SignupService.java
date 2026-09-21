package com.workernotfound.auth.domain.auth.service;

import com.workernotfound.auth.domain.account.entity.enums.MemberRole;
import com.workernotfound.auth.domain.account.repository.AuthAccountRepository;
import com.workernotfound.auth.domain.account.repository.OAuthConnectionRepository;
import com.workernotfound.auth.domain.auth.dto.request.LocationRequest;
import com.workernotfound.auth.domain.auth.dto.request.OAuthOwnerSignupRequest;
import com.workernotfound.auth.domain.auth.dto.request.OAuthWorkerSignupRequest;
import com.workernotfound.auth.domain.auth.dto.request.OwnerSignupRequest;
import com.workernotfound.auth.domain.auth.dto.request.WorkerSignupRequest;
import com.workernotfound.auth.domain.auth.dto.response.SignupResponse;
import com.workernotfound.auth.domain.auth.entity.enums.VerificationPurpose;
import com.workernotfound.auth.domain.auth.exception.AuthErrorCode;
import com.workernotfound.auth.external.client.member.MemberServiceClient;
import com.workernotfound.auth.external.client.member.MemberServiceClientException;
import com.workernotfound.auth.external.client.member.dto.CreateMemberResponse;
import com.workernotfound.auth.external.client.member.dto.CreateOwnerMemberRequest;
import com.workernotfound.auth.external.client.member.dto.CreateWorkerMemberRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;

@Service
@Slf4j
@RequiredArgsConstructor
public class SignupService {

	private final AuthAccountRepository authAccountRepository;
	private final OAuthConnectionRepository oAuthConnectionRepository;
	private final VerificationService verificationService;
	private final OAuthSignupTicketService oAuthSignupTicketService;
	private final SignupPersistenceService signupPersistenceService;
	private final MemberServiceClient memberServiceClient;

	public SignupResponse signupOwner(OwnerSignupRequest request) {
		validateSignupPrerequisites(request.email(), request.phoneNumber());
		CreateMemberResponse memberResponse =
				memberServiceClient.createOwner(toCreateOwnerMemberRequest(request));
		try {
			return signupPersistenceService.saveLocalAccountAndIssueToken(
					memberResponse.memberId(),
					request.email(),
					MemberRole.OWNER,
					request.password(),
					request.deviceId());
		} catch (RuntimeException exception) {
			compensateCreatedMember(memberResponse.memberId(), exception);
			throw translatePersistenceFailure(exception);
		}
	}

	public SignupResponse signupWorker(WorkerSignupRequest request) {
		validateSignupPrerequisites(request.email(), request.phoneNumber());
		CreateMemberResponse memberResponse =
				memberServiceClient.createWorker(toCreateWorkerMemberRequest(request));
		try {
			return signupPersistenceService.saveLocalAccountAndIssueToken(
					memberResponse.memberId(),
					request.email(),
					MemberRole.WORKER,
					request.password(),
					request.deviceId());
		} catch (RuntimeException exception) {
			compensateCreatedMember(memberResponse.memberId(), exception);
			throw translatePersistenceFailure(exception);
		}
	}

	public SignupResponse signupOAuthOwner(OAuthOwnerSignupRequest request) {
		OAuthSignupTicket signupTicket = oAuthSignupTicketService.getAndDelete(request.signupTicket());
		validateOAuthSignupPrerequisites(signupTicket, request.phoneNumber());
		CreateMemberResponse memberResponse;
		try {
			memberResponse =
					memberServiceClient.createOwner(toCreateOwnerMemberRequest(request, signupTicket));
		} catch (MemberServiceClientException exception) {
			restoreOAuthTicketAfterRejectedOwnerSignup(request.signupTicket(), signupTicket, exception);
			throw exception;
		}
		try {
			return signupPersistenceService.saveOAuthAccountAndIssueToken(
					memberResponse.memberId(), signupTicket, MemberRole.OWNER, request.deviceId());
		} catch (RuntimeException exception) {
			compensateCreatedMember(memberResponse.memberId(), exception);
			throw translatePersistenceFailure(exception);
		}
	}

	private void restoreOAuthTicketAfterRejectedOwnerSignup(
			String ticket, OAuthSignupTicket signupTicket, MemberServiceClientException exception) {
		if (exception.getErrorCode() != null) {
			oAuthSignupTicketService.restore(ticket, signupTicket);
		}
	}

	public SignupResponse signupOAuthWorker(OAuthWorkerSignupRequest request) {
		OAuthSignupTicket signupTicket = oAuthSignupTicketService.getAndDelete(request.signupTicket());
		validateOAuthSignupPrerequisites(signupTicket, request.phoneNumber());
		CreateMemberResponse memberResponse =
				memberServiceClient.createWorker(toCreateWorkerMemberRequest(request, signupTicket));
		try {
			return signupPersistenceService.saveOAuthAccountAndIssueToken(
					memberResponse.memberId(), signupTicket, MemberRole.WORKER, request.deviceId());
		} catch (RuntimeException exception) {
			compensateCreatedMember(memberResponse.memberId(), exception);
			throw translatePersistenceFailure(exception);
		}
	}

	private void validateSignupPrerequisites(String email, String phoneNumber) {
		if (!verificationService.isEmailVerified(VerificationPurpose.SIGNUP, email)) {
			throw new SignupException(AuthErrorCode.EMAIL_NOT_VERIFIED);
		}
		if (!verificationService.isSmsVerified(VerificationPurpose.SIGNUP, phoneNumber)) {
			throw new SignupException(AuthErrorCode.PHONE_NOT_VERIFIED);
		}
		if (authAccountRepository.existsByEmail(email)) {
			throw new SignupException(AuthErrorCode.EMAIL_ALREADY_EXISTS);
		}
	}

	private void validateOAuthSignupPrerequisites(
			OAuthSignupTicket signupTicket, String phoneNumber) {
		if (!verificationService.isSmsVerified(VerificationPurpose.SIGNUP, phoneNumber)) {
			throw new SignupException(AuthErrorCode.PHONE_NOT_VERIFIED);
		}
		if (authAccountRepository.existsByEmail(signupTicket.providerEmail())) {
			throw new SignupException(AuthErrorCode.EMAIL_ALREADY_EXISTS);
		}
		if (oAuthConnectionRepository.existsByProviderAndProviderUserId(
				signupTicket.provider(), signupTicket.providerUserId())) {
			throw new SignupException(AuthErrorCode.OAUTH_ALREADY_CONNECTED);
		}
	}

	private RuntimeException translatePersistenceFailure(RuntimeException exception) {
		if (!(exception instanceof DataIntegrityViolationException)) return exception;
		for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
			if (!(cause instanceof ConstraintViolationException violation)) continue;
			String constraint = violation.getConstraintName();
			if (constraint == null) return exception;
			String name = constraint.substring(constraint.lastIndexOf('.') + 1);
			AuthErrorCode errorCode = switch (name) {
				case "uk_auth_accounts_email" -> AuthErrorCode.EMAIL_ALREADY_EXISTS;
				case "uk_oauth_connections_provider_user" -> AuthErrorCode.OAUTH_ALREADY_CONNECTED;
				default -> null;
			};
			if (errorCode == null) return exception;
			SignupException conflict = new SignupException(errorCode);
			conflict.initCause(exception);
			return conflict;
		}
		return exception;
	}

	private void compensateCreatedMember(Long memberId, RuntimeException originalException) {
		try {
			memberServiceClient.deleteMemberForSignupCompensation(memberId);
		} catch (RuntimeException compensationException) {
			log.warn("member-service 회원가입 보상 삭제에 실패했습니다. memberId={}, type={}",
				memberId, compensationException.getClass().getName());
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
				request.storeName(),
				request.businessType(),
				toMemberLocationRequest(request.storeLocation()));
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
				toMemberAvailableTimeRequests(request));
	}

	private com.workernotfound.auth.external.client.member.dto.LocationRequest
			toMemberLocationRequest(LocationRequest request) {
		return new com.workernotfound.auth.external.client.member.dto.LocationRequest(
				request.address(), request.detailAddress(), request.latitude(), request.longitude());
	}

	private List<com.workernotfound.auth.external.client.member.dto.WorkerAvailableTimeRequest>
			toMemberAvailableTimeRequests(WorkerSignupRequest request) {
		return request.availableTimes().stream()
				.map(
						availableTime ->
								new com.workernotfound.auth.external.client.member.dto.WorkerAvailableTimeRequest(
										availableTime.dayOfWeek(), availableTime.startTime(), availableTime.endTime()))
				.toList();
	}

	private CreateOwnerMemberRequest toCreateOwnerMemberRequest(
			OAuthOwnerSignupRequest request, OAuthSignupTicket signupTicket) {
		return new CreateOwnerMemberRequest(
				request.name(),
				signupTicket.providerEmail(),
				request.phoneNumber(),
				com.workernotfound.auth.external.client.member.dto.MemberRole.OWNER,
				request.businessRegistrationNumber(),
				request.storeName(),
				request.businessType(),
				toMemberLocationRequest(request.storeLocation()));
	}

	private CreateWorkerMemberRequest toCreateWorkerMemberRequest(
			OAuthWorkerSignupRequest request, OAuthSignupTicket signupTicket) {
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
				toMemberAvailableTimeRequests(request));
	}

	private List<com.workernotfound.auth.external.client.member.dto.WorkerAvailableTimeRequest>
			toMemberAvailableTimeRequests(OAuthWorkerSignupRequest request) {
		return request.availableTimes().stream()
				.map(
						availableTime ->
								new com.workernotfound.auth.external.client.member.dto.WorkerAvailableTimeRequest(
										availableTime.dayOfWeek(), availableTime.startTime(), availableTime.endTime()))
				.toList();
	}
}
