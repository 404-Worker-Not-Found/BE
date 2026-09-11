package com.workernotfound.matching.domain.application.repository;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.entity.ApplicationStatusHistory;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationActorType;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class ApplicationRepositoryTests extends IntegrationTestSupport {

	private static final LocalDateTime APPLIED_AT = LocalDateTime.of(2026, 9, 11, 10, 30);

	@Autowired
	private ApplicationRepository applicationRepository;

	@Autowired
	private ApplicationStatusHistoryRepository historyRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void savesApplicationAndInitialStatusHistory() {
		Application application = saveApplication(10L, 20L, 30L);
		ApplicationStatusHistory history = saveInitialHistory(application);

		assertThat(applicationRepository.findByJobPostIdAndWorkerMemberId(10L, 20L))
			.contains(application);
		assertThat(applicationRepository.findByJobApplicationAdmissionId(30L))
			.contains(application);
		assertThat(application.getStatus()).isEqualTo(ApplicationStatus.APPLIED);
		assertThat(application.getVersion()).isZero();
		assertThat(application.getRevision()).isEqualTo(1L);
		assertThat(application.getCreatedAt()).isNotNull();
		assertThat(application.getUpdatedAt()).isNotNull();
		assertThat(historyRepository.findByApplicationIdOrderByRevisionAsc(application.getId()))
			.containsExactly(history);
	}

	@Test
	void rejectsDuplicateApplicationForSameJobAndWorker() {
		saveApplication(10L, 20L, 30L);

		assertThatThrownBy(() -> saveApplication(10L, 20L, 31L))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rejectsDuplicateApplicationAdmission() {
		saveApplication(10L, 20L, 30L);

		assertThatThrownBy(() -> saveApplication(11L, 21L, 30L))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rejectsDuplicateHistoryRevisionForApplication() {
		Application application = saveApplication(10L, 20L, 30L);
		saveInitialHistory(application);

		assertThatThrownBy(() -> saveInitialHistory(application))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void preventsDeletingApplicationWithHistory() {
		Application application = saveApplication(10L, 20L, 30L);
		saveInitialHistory(application);

		assertThatThrownBy(() -> jdbcTemplate.update(
			"DELETE FROM applications WHERE id = ?",
			application.getId()
		))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private Application saveApplication(Long jobPostId, Long workerMemberId, Long admissionId) {
		Application application = Application.builder()
			.jobPostId(jobPostId)
			.workerMemberId(workerMemberId)
			.jobApplicationAdmissionId(admissionId)
			.appliedAt(APPLIED_AT)
			.build();
		return applicationRepository.saveAndFlush(application);
	}

	private ApplicationStatusHistory saveInitialHistory(Application application) {
		ApplicationStatusHistory history = ApplicationStatusHistory.builder()
			.application(application)
			.toStatus(ApplicationStatus.APPLIED)
			.actorType(ApplicationActorType.WORKER)
			.actorMemberId(application.getWorkerMemberId())
			.reasonCode("APPLICATION_SUBMITTED")
			.revision(1L)
			.changedAt(APPLIED_AT)
			.build();
		return historyRepository.saveAndFlush(history);
	}
}
