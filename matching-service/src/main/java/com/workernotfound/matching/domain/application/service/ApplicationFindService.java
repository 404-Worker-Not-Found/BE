package com.workernotfound.matching.domain.application.service;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.domain.application.exception.ApplicationErrorCode;
import com.workernotfound.matching.domain.application.model.OwnerApplicantRow;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.global.exception.BusinessException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ApplicationFindService {

	private final ApplicationRepository applicationRepository;

	public Optional<Application> findExistingApplication(Long jobPostId, Long workerMemberId) {
		return applicationRepository.findByJobPostIdAndWorkerMemberId(jobPostId, workerMemberId);
	}

	public Application findApplication(Long applicationId) {
		return applicationRepository.findById(applicationId)
			.orElseThrow(() -> new BusinessException(ApplicationErrorCode.APPLICATION_NOT_FOUND));
	}

	public Application findOwnedApplication(Long applicationId, Long workerMemberId) {
		Application application = findApplication(applicationId);
		if (!application.getWorkerMemberId().equals(workerMemberId)) {
			throw new BusinessException(ApplicationErrorCode.APPLICATION_FORBIDDEN);
		}
		return application;
	}

	public Page<Application> findWorkerApplications(Long workerMemberId, int page, int size) {
		Sort sort = Sort.by(Sort.Order.desc("appliedAt"), Sort.Order.desc("id"));
		return applicationRepository.findByWorkerMemberId(workerMemberId, PageRequest.of(page, size, sort));
	}

	public Page<OwnerApplicantRow> findOwnerApplicants(
		Long jobPostId,
		Long ownerMemberId,
		Long scoreBatchId,
		int page,
		int size
	) {
		return applicationRepository.findOwnerApplicantRows(
			jobPostId,
			ownerMemberId,
			scoreBatchId,
			ApplicationStatus.APPLIED,
			PageRequest.of(page, size)
		);
	}

	public void validateOwnerAccess(Long jobPostId, Long ownerMemberId) {
		if (applicationRepository.existsByJobPostId(jobPostId)
			&& !applicationRepository.existsByJobPostIdAndOwnerMemberId(jobPostId, ownerMemberId)) {
			throw new BusinessException(ApplicationErrorCode.APPLICATION_FORBIDDEN);
		}
	}

	public Application findOwnerApplicant(Long jobPostId, Long applicationId, Long ownerMemberId) {
		return applicationRepository.findByIdAndJobPostIdAndOwnerMemberId(
			applicationId,
			jobPostId,
			ownerMemberId
		).orElseThrow(() -> new BusinessException(ApplicationErrorCode.APPLICATION_FORBIDDEN));
	}
}
