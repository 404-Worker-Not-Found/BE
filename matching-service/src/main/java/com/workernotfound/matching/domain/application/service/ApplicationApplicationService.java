package com.workernotfound.matching.domain.application.service;

import com.workernotfound.matching.domain.application.dto.request.CreateApplicationRequest;
import com.workernotfound.matching.domain.application.dto.response.ApplicationListResponse;
import com.workernotfound.matching.domain.application.dto.response.ApplicationResponse;
import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.domain.application.exception.ApplicationErrorCode;
import com.workernotfound.matching.external.client.job.JobServiceClient;
import com.workernotfound.matching.external.client.job.JobServiceClientException;
import com.workernotfound.matching.external.client.job.dto.ApplicationAdmissionResponse;
import com.workernotfound.matching.external.client.member.MemberServiceClient;
import com.workernotfound.matching.external.client.member.MemberServiceClientException;
import com.workernotfound.matching.external.client.member.dto.MemberInternalResponse;
import com.workernotfound.matching.global.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ApplicationApplicationService {

	private static final String ACTIVE_STATUS = "ACTIVE";
	private static final String WORKER_ROLE = "WORKER";

	private final ApplicationCommandService applicationCommandService;
	private final ApplicationFindService applicationFindService;
	private final MemberServiceClient memberServiceClient;
	private final JobServiceClient jobServiceClient;

	public ApplicationResponse create(Long workerMemberId, CreateApplicationRequest request) {
		Optional<Application> existing = applicationFindService.findExistingApplication(
			request.jobPostId(),
			workerMemberId
		);
		if (existing.isPresent()) {
			return handleExistingApplication(existing.get());
		}

		validateWorker(workerMemberId);
		ApplicationAdmissionResponse admission = createAdmission(request.jobPostId(), workerMemberId);
		validateAdmission(admission, request.jobPostId());
		try {
			return ApplicationResponse.from(applicationCommandService.create(
				admission.jobPostId(),
				workerMemberId,
				admission.admissionId(),
				admission.admittedAt()
			));
		} catch (DataIntegrityViolationException exception) {
			return resolveConcurrentApplication(request.jobPostId(), workerMemberId, exception);
		}
	}

	public ApplicationResponse getApplication(Long applicationId, Long workerMemberId) {
		return ApplicationResponse.from(
			applicationFindService.findOwnedApplication(applicationId, workerMemberId)
		);
	}

	public ApplicationListResponse getApplications(Long workerMemberId, int page, int size) {
		return ApplicationListResponse.from(
			applicationFindService.findWorkerApplications(workerMemberId, page, size)
		);
	}

	public ApplicationResponse cancel(Long applicationId, Long workerMemberId) {
		try {
			return ApplicationResponse.from(applicationCommandService.cancel(applicationId, workerMemberId));
		} catch (OptimisticLockingFailureException exception) {
			Application latest = applicationFindService.findOwnedApplication(applicationId, workerMemberId);
			if (latest.getStatus() == ApplicationStatus.CANCELED) {
				return ApplicationResponse.from(latest);
			}
			throw new BusinessException(ApplicationErrorCode.APPLICATION_STATE_CONFLICT);
		}
	}

	private ApplicationResponse handleExistingApplication(Application application) {
		if (application.getStatus() == ApplicationStatus.APPLIED) {
			return ApplicationResponse.from(application);
		}
		throw new BusinessException(ApplicationErrorCode.REAPPLICATION_NOT_ALLOWED);
	}

	private void validateWorker(Long workerMemberId) {
		try {
			MemberInternalResponse member = memberServiceClient.getMember(workerMemberId);
			if (!workerMemberId.equals(member.memberId())
				|| !WORKER_ROLE.equals(member.role())
				|| !ACTIVE_STATUS.equals(member.status())) {
				throw new BusinessException(ApplicationErrorCode.MEMBER_NOT_ELIGIBLE);
			}
		} catch (MemberServiceClientException exception) {
			if (exception.getStatusCode() != null && exception.getStatusCode().value() == 404) {
				throw new BusinessException(ApplicationErrorCode.MEMBER_NOT_ELIGIBLE);
			}
			throw new BusinessException(ApplicationErrorCode.DEPENDENCY_SERVICE_UNAVAILABLE);
		}
	}

	private ApplicationAdmissionResponse createAdmission(Long jobPostId, Long workerMemberId) {
		try {
			return jobServiceClient.createApplicationAdmission(
				jobPostId,
				workerMemberId,
				UUID.randomUUID().toString()
			);
		} catch (JobServiceClientException exception) {
			throw mapJobServiceError(exception);
		}
	}

	private void validateAdmission(
		ApplicationAdmissionResponse admission,
		Long jobPostId
	) {
		if (admission.admissionId() == null
			|| !jobPostId.equals(admission.jobPostId())
			|| admission.admittedAt() == null
			|| admission.expiresAt() == null) {
			throw new BusinessException(ApplicationErrorCode.DEPENDENCY_SERVICE_UNAVAILABLE);
		}
		if (!admission.expiresAt().isAfter(LocalDateTime.now())) {
			throw new BusinessException(ApplicationErrorCode.ADMISSION_EXPIRED);
		}
	}

	private BusinessException mapJobServiceError(JobServiceClientException exception) {
		String responseCode = exception.getResponseCode();
		if (responseCode == null) {
			return new BusinessException(ApplicationErrorCode.DEPENDENCY_SERVICE_UNAVAILABLE);
		}
		return switch (responseCode) {
			case "JOB_NOT_FOUND", "JOB-404-001" -> new BusinessException(ApplicationErrorCode.JOB_NOT_FOUND);
			case "JOB_NOT_OPEN" -> new BusinessException(ApplicationErrorCode.JOB_NOT_OPEN);
			case "APPLICATION_DEADLINE_PASSED" ->
				new BusinessException(ApplicationErrorCode.APPLICATION_DEADLINE_PASSED);
			case "ADMISSION_EXPIRED" -> new BusinessException(ApplicationErrorCode.ADMISSION_EXPIRED);
			default -> new BusinessException(ApplicationErrorCode.DEPENDENCY_SERVICE_UNAVAILABLE);
		};
	}

	private ApplicationResponse resolveConcurrentApplication(
		Long jobPostId,
		Long workerMemberId,
		DataIntegrityViolationException cause
	) {
		return applicationFindService.findExistingApplication(jobPostId, workerMemberId)
			.map(this::handleExistingApplication)
			.orElseThrow(() -> cause);
	}
}
