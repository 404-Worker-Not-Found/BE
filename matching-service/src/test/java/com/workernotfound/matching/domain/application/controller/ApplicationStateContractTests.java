package com.workernotfound.matching.domain.application.controller;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * job-service 지원자 수 projection이 초기 적재·재구축에 쓰는 공고별 지원 상태 스냅샷 계약을 실제 컨트롤러·보안 필터·MySQL로 검증한다.
 */
@AutoConfigureMockMvc
class ApplicationStateContractTests extends IntegrationTestSupport {

	private static final String PATH = "/api/applications/internal/jobs/{jobPostId}/application-states";
	private static final String INTERNAL_SECRET = "matching-test-internal-secret";
	private static final AtomicLong JOB_POST_IDS = new AtomicLong(9_160_000);
	private static final AtomicLong ADMISSION_IDS = new AtomicLong(9_160_000);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ApplicationRepository applicationRepository;

	@Test
	void returnsEveryApplicationOfJobWithStatusAndRevisionInIdOrder() throws Exception {
		long jobPostId = JOB_POST_IDS.incrementAndGet();
		Application applied = saveApplication(jobPostId, 1L);
		Application canceled = saveApplication(jobPostId, 2L);
		canceled.cancel();
		applicationRepository.saveAndFlush(canceled);
		saveApplication(jobPostId + 1, 3L);

		mockMvc.perform(get(PATH, jobPostId).header("X-Internal-Secret", INTERNAL_SECRET))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.jobPostId").value(jobPostId))
			.andExpect(jsonPath("$.data.applications", hasSize(2)))
			.andExpect(jsonPath("$.data.applications[0].applicationId").value(applied.getId()))
			.andExpect(jsonPath("$.data.applications[0].status").value("APPLIED"))
			.andExpect(jsonPath("$.data.applications[0].revision").value(1))
			.andExpect(jsonPath("$.data.applications[1].applicationId").value(canceled.getId()))
			.andExpect(jsonPath("$.data.applications[1].status").value("CANCELED"))
			.andExpect(jsonPath("$.data.applications[1].revision").value(2));
	}

	@Test
	void returnsEmptyListForJobWithoutApplications() throws Exception {
		mockMvc.perform(get(PATH, JOB_POST_IDS.incrementAndGet()).header("X-Internal-Secret", INTERNAL_SECRET))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.applications", hasSize(0)));
	}

	@Test
	void rejectsRequestWithoutValidInternalSecret() throws Exception {
		mockMvc.perform(get(PATH, JOB_POST_IDS.incrementAndGet()).header("X-Internal-Secret", "wrong-secret"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("GLOBAL-401-001"));
	}

	private Application saveApplication(Long jobPostId, Long workerMemberId) {
		return applicationRepository.saveAndFlush(Application.builder()
			.jobPostId(jobPostId)
			.workerMemberId(workerMemberId)
			.ownerMemberId(100L)
			.jobApplicationAdmissionId(ADMISSION_IDS.incrementAndGet())
			.appliedAt(LocalDateTime.now().minusMinutes(10))
			.build());
	}
}
