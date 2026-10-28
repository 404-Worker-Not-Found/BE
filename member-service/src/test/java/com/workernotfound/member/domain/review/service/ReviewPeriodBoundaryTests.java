package com.workernotfound.member.domain.review.service;

import com.workernotfound.member.domain.review.dto.request.WriteReviewRequest;
import com.workernotfound.member.domain.review.exception.ReviewErrorCode;
import com.workernotfound.member.global.exception.BusinessException;
import com.workernotfound.member.support.IntegrationTestSupport;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

// 작성 기간은 근무 완료 시각 + 기간(기본 14일) 직전까지다. 그 시각과 같으면 지난 것으로 본다.
class ReviewPeriodBoundaryTests extends IntegrationTestSupport {
	private static final AtomicLong IDS = new AtomicLong(8_300_000);
	private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
	private static final LocalDateTime COMPLETED_AT = LocalDateTime.of(2026, 10, 1, 18, 0);
	private static final LocalDateTime DEADLINE = COMPLETED_AT.plusDays(14);

	@Autowired
	private ReviewCommandService commands;

	@Autowired
	private ReviewQueryService queries;

	@Autowired
	private ReviewableWorkReceiptService receipts;

	@Autowired
	private JdbcTemplate jdbc;

	@MockitoBean
	private Clock clock;

	@Test
	void reviewCanBeWrittenJustBeforeTheDeadlineButNotAtIt() {
		now(COMPLETED_AT);
		long owner = member("OWNER");
		long worker = member("WORKER");
		long work = IDS.incrementAndGet();
		receipts.receive(new WorkCompletion("boundary-" + work, work, work + 1, owner, worker, LocalDate.of(2026, 10, 1),
			COMPLETED_AT));

		now(DEADLINE.minusNanos(1000));
		assertThat(queries.pending(worker, 0, 20).works()).hasSize(1);
		assertThat(commands.write(worker, new WriteReviewRequest(work, 5, null)).getCreatedAt())
			.isEqualTo(DEADLINE.minusNanos(1000));

		now(DEADLINE);
		assertThat(queries.pending(owner, 0, 20).works()).isEmpty();
		assertThatThrownBy(() -> commands.write(owner, new WriteReviewRequest(work, 5, null)))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ReviewErrorCode.REVIEW_PERIOD_EXPIRED));
	}

	private void now(LocalDateTime time) {
		when(clock.getZone()).thenReturn(ZONE);
		when(clock.instant()).thenReturn(time.atZone(ZONE).toInstant());
	}

	private long member(String role) {
		long id = IDS.incrementAndGet();
		jdbc.update("insert into members(id, created_at, updated_at, name, email, phone_number, role, status, joined_at)"
				+ " values (?, now(6), now(6), ?, ?, ?, ?, 'ACTIVE', now(6))", id, "경계" + id, "boundary" + id + "@test.com",
			"011" + String.format("%08d", id % 100_000_000), role);
		return id;
	}
}
