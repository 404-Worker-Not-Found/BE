package com.workernotfound.matching.domain.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.matching.domain.application.dto.request.CreateApplicationRequest;
import com.workernotfound.matching.domain.application.dto.response.ApplicationResponse;
import com.workernotfound.matching.domain.application.entity.ApplicationStatusHistory;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.domain.application.exception.ApplicationErrorCode;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.application.repository.ApplicationStatusHistoryRepository;
import com.workernotfound.matching.domain.outbox.entity.OutboxEvent;
import com.workernotfound.matching.domain.outbox.entity.enums.OutboxEventStatus;
import com.workernotfound.matching.domain.outbox.repository.OutboxEventRepository;
import com.workernotfound.matching.external.client.job.JobServiceClient;
import com.workernotfound.matching.external.client.job.JobServiceClientException;
import com.workernotfound.matching.external.client.job.dto.ApplicationAdmissionResponse;
import com.workernotfound.matching.external.client.member.MemberServiceClient;
import com.workernotfound.matching.external.client.member.dto.MemberInternalResponse;
import com.workernotfound.matching.global.exception.BusinessException;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Transactional
class ApplicationApplicationServiceTests extends IntegrationTestSupport {

	private static final Long WORKER_MEMBER_ID = 20L;
	private static final Long JOB_POST_ID = 10L;

	@Autowired
	private ApplicationApplicationService applicationService;

	@Autowired
	private ApplicationRepository applicationRepository;

	@Autowired
	private ApplicationStatusHistoryRepository historyRepository;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private MemberServiceClient memberServiceClient;

	@MockitoBean
	private JobServiceClient jobServiceClient;

	@BeforeEach
	void cleanUpPersistence() {
		outboxEventRepository.deleteAll();
		historyRepository.deleteAll();
		applicationRepository.deleteAll();
	}

	@Test
	void createsApplicationAndInitialHistoryAfterEligibilityChecks() throws Exception {
		stubEligibleWorker();
		stubAdmission(30L);

		ApplicationResponse response = applicationService.create(
			WORKER_MEMBER_ID,
			new CreateApplicationRequest(JOB_POST_ID)
		);

		assertThat(response.status()).isEqualTo(ApplicationStatus.APPLIED);
		assertThat(response.revision()).isEqualTo(1L);
		List<ApplicationStatusHistory> histories = historyRepository
			.findByApplicationIdOrderByRevisionAsc(response.applicationId());
		assertThat(histories).hasSize(1);
		assertThat(histories.get(0).getToStatus()).isEqualTo(ApplicationStatus.APPLIED);
		OutboxEvent event = outboxEventRepository.findAll().get(0);
		JsonNode payload = objectMapper.readTree(event.getPayload());
		assertThat(event.getEventType()).isEqualTo("ApplicationSubmitted");
		assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(event.getRevision()).isEqualTo(1L);
		assertThat(payload.path("eventId").textValue()).isEqualTo(event.getEventId());
		assertThat(payload.path("applicationId").longValue()).isEqualTo(response.applicationId());
		assertThat(payload.path("status").textValue()).isEqualTo("APPLIED");
	}

	@Test
	void returnsExistingAppliedApplicationWithoutCallingDependenciesAgain() {
		stubEligibleWorker();
		stubAdmission(31L);
		ApplicationResponse first = applicationService.create(
			WORKER_MEMBER_ID,
			new CreateApplicationRequest(JOB_POST_ID)
		);

		ApplicationResponse second = applicationService.create(
			WORKER_MEMBER_ID,
			new CreateApplicationRequest(JOB_POST_ID)
		);

		assertThat(second.applicationId()).isEqualTo(first.applicationId());
		verify(memberServiceClient, times(1)).getMember(WORKER_MEMBER_ID);
		verify(jobServiceClient, times(1))
			.createApplicationAdmission(org.mockito.ArgumentMatchers.eq(JOB_POST_ID),
				org.mockito.ArgumentMatchers.eq(WORKER_MEMBER_ID), anyString());
	}

	@Test
	void cancelsApplicationOnceAndReturnsCanceledApplicationOnRetry() {
		stubEligibleWorker();
		stubAdmission(32L);
		ApplicationResponse created = applicationService.create(
			WORKER_MEMBER_ID,
			new CreateApplicationRequest(JOB_POST_ID)
		);

		ApplicationResponse canceled = applicationService.cancel(created.applicationId(), WORKER_MEMBER_ID);
		ApplicationResponse retried = applicationService.cancel(created.applicationId(), WORKER_MEMBER_ID);

		assertThat(canceled.status()).isEqualTo(ApplicationStatus.CANCELED);
		assertThat(canceled.revision()).isEqualTo(2L);
		assertThat(retried.status()).isEqualTo(ApplicationStatus.CANCELED);
		assertThat(historyRepository.findByApplicationIdOrderByRevisionAsc(created.applicationId()))
			.extracting(ApplicationStatusHistory::getToStatus)
			.containsExactly(ApplicationStatus.APPLIED, ApplicationStatus.CANCELED);
		assertThat(outboxEventRepository.findAll())
			.extracting(OutboxEvent::getEventType)
			.containsExactly("ApplicationSubmitted", "ApplicationCanceled");
	}

	@Test
	void rejectsReapplicationAfterCancellation() {
		stubEligibleWorker();
		stubAdmission(33L);
		ApplicationResponse created = applicationService.create(
			WORKER_MEMBER_ID,
			new CreateApplicationRequest(JOB_POST_ID)
		);
		applicationService.cancel(created.applicationId(), WORKER_MEMBER_ID);

		assertThatThrownBy(() -> applicationService.create(
			WORKER_MEMBER_ID,
			new CreateApplicationRequest(JOB_POST_ID)
		))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.getErrorCode()).isEqualTo(ApplicationErrorCode.REAPPLICATION_NOT_ALLOWED));
	}

	@Test
	void rejectsApplicationWhenMemberIsNotActiveWorker() {
		when(memberServiceClient.getMember(WORKER_MEMBER_ID)).thenReturn(new MemberInternalResponse(
			WORKER_MEMBER_ID,
			"OWNER",
			"ACTIVE"
		));

		assertThatThrownBy(() -> applicationService.create(
			WORKER_MEMBER_ID,
			new CreateApplicationRequest(JOB_POST_ID)
		))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.getErrorCode()).isEqualTo(ApplicationErrorCode.MEMBER_NOT_ELIGIBLE));
		assertThat(applicationRepository.count()).isZero();
		verifyNoInteractions(jobServiceClient);
	}

	@Test
	void mapsClosedJobResponseToJobNotOpen() {
		stubEligibleWorker();
		when(jobServiceClient.createApplicationAdmission(
			org.mockito.ArgumentMatchers.eq(JOB_POST_ID),
			org.mockito.ArgumentMatchers.eq(WORKER_MEMBER_ID),
			anyString()
		)).thenThrow(new JobServiceClientException(HttpStatus.CONFLICT, "JOB_NOT_OPEN"));

		assertThatThrownBy(() -> applicationService.create(
			WORKER_MEMBER_ID,
			new CreateApplicationRequest(JOB_POST_ID)
		))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.getErrorCode()).isEqualTo(ApplicationErrorCode.JOB_NOT_OPEN));
		assertThat(applicationRepository.count()).isZero();
	}

	@Test
	void rejectsAnotherWorkerApplicationLookupAndCancellation() {
		stubEligibleWorker();
		stubAdmission(34L);
		ApplicationResponse created = applicationService.create(
			WORKER_MEMBER_ID,
			new CreateApplicationRequest(JOB_POST_ID)
		);

		assertThatThrownBy(() -> applicationService.getApplication(created.applicationId(), 99L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.getErrorCode()).isEqualTo(ApplicationErrorCode.APPLICATION_FORBIDDEN));
		assertThatThrownBy(() -> applicationService.cancel(created.applicationId(), 99L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.getErrorCode()).isEqualTo(ApplicationErrorCode.APPLICATION_FORBIDDEN));
	}

	private void stubEligibleWorker() {
		when(memberServiceClient.getMember(WORKER_MEMBER_ID)).thenReturn(new MemberInternalResponse(
			WORKER_MEMBER_ID,
			"WORKER",
			"ACTIVE"
		));
	}

	private void stubAdmission(Long admissionId) {
		when(jobServiceClient.createApplicationAdmission(
			org.mockito.ArgumentMatchers.eq(JOB_POST_ID),
			org.mockito.ArgumentMatchers.eq(WORKER_MEMBER_ID),
			anyString()
		)).thenReturn(new ApplicationAdmissionResponse(
			admissionId,
			JOB_POST_ID,
			1L,
			100L,
			1L,
			LocalDate.now().plusDays(1),
			LocalTime.of(9, 0),
			LocalTime.of(18, 0),
			BigDecimal.valueOf(37.5),
			BigDecimal.valueOf(127.0),
			LocalDateTime.now(),
			LocalDateTime.now().plusMinutes(1)
		));
	}
}
