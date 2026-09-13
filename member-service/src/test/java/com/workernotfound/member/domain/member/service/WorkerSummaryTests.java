package com.workernotfound.member.domain.member.service;

import com.workernotfound.member.domain.member.entity.Member;
import com.workernotfound.member.domain.member.entity.enums.MemberRole;
import com.workernotfound.member.domain.member.repository.MemberRepository;
import com.workernotfound.member.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class WorkerSummaryTests extends IntegrationTestSupport {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MemberRepository memberRepository;

	@AfterEach
	void tearDown() {
		memberRepository.deleteAll();
	}

	@Test
	void returnsOnlyActiveWorkerMinimumInformation() throws Exception {
		Member worker = memberRepository.save(Member.builder()
			.name("지원자")
			.email("worker@example.com")
			.phoneNumber("01012345678")
			.role(MemberRole.WORKER)
			.build());
		Member owner = memberRepository.save(Member.builder()
			.name("점주")
			.email("owner@example.com")
			.phoneNumber("01087654321")
			.role(MemberRole.OWNER)
			.build());

		mockMvc.perform(post("/api/members/internal/workers/summaries")
				.header("X-Internal-Secret", "test-internal-secret")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"memberIds\":[%d,%d,99999]}".formatted(worker.getId(), owner.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(1))
			.andExpect(jsonPath("$.data[0].memberId").value(worker.getId()))
			.andExpect(jsonPath("$.data[0].name").value("지원자"))
			.andExpect(jsonPath("$.data[0].email").doesNotExist())
			.andExpect(jsonPath("$.data[0].phoneNumber").doesNotExist());
	}

	@Test
	void rejectsEmptyWorkerIds() throws Exception {
		mockMvc.perform(post("/api/members/internal/workers/summaries")
				.header("X-Internal-Secret", "test-internal-secret")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"memberIds\":[]}"))
			.andExpect(status().isBadRequest());
	}
}
