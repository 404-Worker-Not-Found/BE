package com.workernotfound.member.domain.member.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.workernotfound.member.domain.member.repository.MemberRepository;
import com.workernotfound.member.support.IntegrationTestSupport;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class MemberErrorContractTests extends IntegrationTestSupport {
	static final AtomicLong PHONES = new AtomicLong(1010000000L);
	@Autowired MockMvc mvc;
	@Autowired MemberRepository members;

	String email() {
		return UUID.randomUUID() + "@example.com";
	}

	String phone() {
		return "0" + PHONES.incrementAndGet();
	}

	String body(String email, String phone) {
		return """
					 {"name":"알바생","email":"%s","phoneNumber":"%s","role":"WORKER",
						"desiredHourlyWage":12000,"activityRadiusKm":5,"immediatelyAvailable":true,
						"baseLocation":{"address":"서울","detailAddress":"1층","latitude":37.5,"longitude":127.0},
						"preferredBusinessTypes":["CAFE"],
						"availableTimes":[{"dayOfWeek":"MONDAY","startTime":"09:00:00","endTime":"18:00:00"}]}
					 """
				.formatted(email, phone);
	}

	org.springframework.test.web.servlet.ResultActions create(String json) throws Exception {
		return mvc.perform(
				post("/api/members/internal/workers")
						.header("X-Internal-Secret", "test-internal-secret")
						.contentType("application/json")
						.content(json));
	}

	@Test
	void duplicateEmailAndPhoneHaveDistinct409Codes() throws Exception {
		String email = email(), phone = phone();
		create(body(email, phone)).andExpect(status().isCreated());
		create(body(email, phone()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("MEMBER-409-001"));
		String another = email();
		create(body(another, phone))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("MEMBER-409-002"));
		assertThat(members.existsByEmail(another)).isFalse();
	}

	@Test
	void invalidWorkerDetailsRollBackAndKeepBusinessCodes() throws Exception {
		String email = email();
		create(body(email, phone()).replace("18:00:00", "08:00:00"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("WORKER-400-002"));
		assertThat(members.existsByEmail(email)).isFalse();
		create(body(email, phone()).replace("[\"CAFE\"]", "[\"CAFE\",\"CAFE\"]"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("WORKER-400-001"));
		assertThat(members.existsByEmail(email)).isFalse();
		create(body(email, phone()).replace("WORKER", "OWNER"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MEMBER-400-001"));
	}

	@Test
	void missingMemberIs404RatherThanInputError() throws Exception {
		mvc.perform(
						get("/api/members/internal/" + Long.MAX_VALUE)
								.header("X-Internal-Secret", "test-internal-secret"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("MEMBER-404-001"));
	}
}
