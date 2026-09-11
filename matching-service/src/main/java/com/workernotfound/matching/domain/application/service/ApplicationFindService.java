package com.workernotfound.matching.domain.application.service;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.exception.ApplicationErrorCode;
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
}
