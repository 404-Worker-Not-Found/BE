package com.workernotfound.member.domain.review.controller;

import com.workernotfound.member.domain.member.entity.enums.MemberRole;
import com.workernotfound.member.domain.review.service.ReviewableWorkReceiptService;
import com.workernotfound.member.domain.review.service.WorkCompletion;
import com.workernotfound.member.global.security.AuthenticatedMember;
import com.workernotfound.member.support.IntegrationTestSupport;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ReviewApiTests extends IntegrationTestSupport {
	private static final AtomicLong IDS = new AtomicLong(8_400_000);

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ReviewableWorkReceiptService receipts;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void writesReviewAndShowsItInReceivedReviewsAndPendingList() throws Exception {
		long owner = member("OWNER");
		long worker = member("WORKER");
		long work = completedWork(owner, worker);

		mvc.perform(get("/api/members/me/reviews/pending").with(as(worker, MemberRole.WORKER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.works[0].workId").value(work))
			.andExpect(jsonPath("$.data.works[0].myRole").value("WORKER"))
			.andExpect(jsonPath("$.data.works[0].revieweeMemberId").value(owner));

		mvc.perform(post("/api/members/me/reviews").with(as(worker, MemberRole.WORKER))
				.contentType("application/json").content(body(work, "4", "\"시간 약속을 잘 지켜요\"")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.workId").value(work))
			.andExpect(jsonPath("$.data.revieweeMemberId").value(owner))
			.andExpect(jsonPath("$.data.reviewerRole").value("WORKER"))
			.andExpect(jsonPath("$.data.rating").value(4));

		mvc.perform(get("/api/members/" + owner + "/reviews").with(as(worker, MemberRole.WORKER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.reviewCount").value(1))
			.andExpect(jsonPath("$.data.averageRating").value(4.0))
			.andExpect(jsonPath("$.data.reviews[0].comment").value("시간 약속을 잘 지켜요"))
			.andExpect(jsonPath("$.data.reviews[0].reviewerMemberId").doesNotExist());

		mvc.perform(get("/api/members/me/reviews/pending").with(as(worker, MemberRole.WORKER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalCount").value(0));
		mvc.perform(post("/api/members/me/reviews").with(as(worker, MemberRole.WORKER))
				.contentType("application/json").content(body(work, "5", "null")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("REVIEW-409-001"));
	}

	@Test
	void rejectsInvalidRequestsAndUnauthenticatedCalls() throws Exception {
		long owner = member("OWNER");
		long worker = member("WORKER");
		long work = completedWork(owner, worker);

		mvc.perform(post("/api/members/me/reviews").contentType("application/json").content(body(work, "5", "null")))
			.andExpect(status().isUnauthorized());
		for (String rating : List.of("0", "6", "null", "4.5")) {
			mvc.perform(post("/api/members/me/reviews").with(as(worker, MemberRole.WORKER))
					.contentType("application/json").content(body(work, rating, "null")))
				.andExpect(status().isBadRequest());
		}
		mvc.perform(post("/api/members/me/reviews").with(as(worker, MemberRole.WORKER))
				.contentType("application/json").content(body(work, "5", "\"" + "가".repeat(501) + "\"")))
			.andExpect(status().isBadRequest());
		mvc.perform(post("/api/members/me/reviews").with(as(worker, MemberRole.WORKER))
				.contentType("application/json").content("{\"rating\":5}"))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/members/me/reviews/pending").param("size", "101").with(as(worker, MemberRole.WORKER)))
			.andExpect(status().isBadRequest());
		mvc.perform(post("/api/members/me/reviews").with(as(member("WORKER"), MemberRole.WORKER))
				.contentType("application/json").content(body(work, "5", "null")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("REVIEW-403-001"));
		mvc.perform(post("/api/members/me/reviews").with(as(worker, MemberRole.WORKER))
				.contentType("application/json").content(body(IDS.incrementAndGet(), "5", "null")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("REVIEW-404-001"));
		mvc.perform(get("/api/members/" + IDS.incrementAndGet() + "/reviews").with(as(worker, MemberRole.WORKER)))
			.andExpect(status().isNotFound());
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.paths['/api/members/me/reviews']").exists())
			.andExpect(jsonPath("$.paths['/api/members/me/reviews/pending']").exists())
			.andExpect(jsonPath("$.paths['/api/members/{memberId}/reviews']").exists());
	}

	private static RequestPostProcessor as(long memberId, MemberRole role) {
		return authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedMember(memberId + 1, memberId, role),
			null, List.of()));
	}

	private static String body(long workId, String rating, String comment) {
		return """
			{"workId":%d,"rating":%s,"comment":%s}
			""".formatted(workId, rating, comment);
	}

	private long member(String role) {
		long id = IDS.incrementAndGet();
		jdbc.update("insert into members(id, created_at, updated_at, name, email, phone_number, role, status, joined_at)"
				+ " values (?, now(6), now(6), ?, ?, ?, ?, 'ACTIVE', now(6))", id, "API" + id, "api" + id + "@test.com",
			"012" + String.format("%08d", id % 100_000_000), role);
		return id;
	}

	private long completedWork(long owner, long worker) {
		long workId = IDS.incrementAndGet();
		receipts.receive(new WorkCompletion("api-" + workId, workId, workId + 1, owner, worker, LocalDate.of(2026, 10, 27),
			LocalDateTime.now(ZoneId.of("Asia/Seoul")).minusHours(1)));
		return workId;
	}
}
