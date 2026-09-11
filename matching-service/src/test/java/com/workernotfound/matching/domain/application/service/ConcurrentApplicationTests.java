package com.workernotfound.matching.domain.application.service;

import com.workernotfound.matching.domain.application.dto.request.CreateApplicationRequest;
import com.workernotfound.matching.domain.application.dto.response.ApplicationResponse;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.application.repository.ApplicationStatusHistoryRepository;
import com.workernotfound.matching.domain.outbox.repository.OutboxEventRepository;
import com.workernotfound.matching.external.client.job.JobServiceClient;
import com.workernotfound.matching.external.client.job.dto.ApplicationAdmissionResponse;
import com.workernotfound.matching.external.client.member.MemberServiceClient;
import com.workernotfound.matching.external.client.member.dto.MemberInternalResponse;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class ConcurrentApplicationTests extends IntegrationTestSupport {

	@Autowired
	private ApplicationApplicationService applicationService;

	@Autowired
	private ApplicationRepository applicationRepository;

	@Autowired
	private ApplicationStatusHistoryRepository historyRepository;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@MockitoBean
	private MemberServiceClient memberServiceClient;

	@MockitoBean
	private JobServiceClient jobServiceClient;

	private ExecutorService executorService;

	@BeforeEach
	void setUp() {
		outboxEventRepository.deleteAll();
		historyRepository.deleteAll();
		applicationRepository.deleteAll();
		executorService = Executors.newFixedThreadPool(2);
	}

	@AfterEach
	void tearDown() {
		executorService.shutdownNow();
	}

	@Test
	void concurrentDuplicateRequestsReturnOneApplication() throws Exception {
		Long workerMemberId = 20L;
		Long jobPostId = 10L;
		CountDownLatch admissionRequests = new CountDownLatch(2);
		AtomicLong admissionIds = new AtomicLong(100L);
		when(memberServiceClient.getMember(workerMemberId))
			.thenReturn(new MemberInternalResponse(workerMemberId, "WORKER", "ACTIVE"));
		when(jobServiceClient.createApplicationAdmission(anyLong(), anyLong(), anyString()))
			.thenAnswer(invocation -> {
				admissionRequests.countDown();
				assertThat(admissionRequests.await(5, TimeUnit.SECONDS)).isTrue();
				return admission(admissionIds.incrementAndGet(), jobPostId);
			});

		Future<ApplicationResponse> first = submitApplication(workerMemberId, jobPostId);
		Future<ApplicationResponse> second = submitApplication(workerMemberId, jobPostId);

		assertThat(first.get(10, TimeUnit.SECONDS).applicationId())
			.isEqualTo(second.get(10, TimeUnit.SECONDS).applicationId());
		assertThat(applicationRepository.count()).isOne();
		assertThat(historyRepository.count()).isOne();
		assertThat(outboxEventRepository.count()).isOne();
	}

	private Future<ApplicationResponse> submitApplication(Long workerMemberId, Long jobPostId) {
		return executorService.submit(() -> applicationService.create(
			workerMemberId,
			new CreateApplicationRequest(jobPostId)
		));
	}

	private ApplicationAdmissionResponse admission(Long admissionId, Long jobPostId) {
		return new ApplicationAdmissionResponse(
			admissionId,
			jobPostId,
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
		);
	}
}
