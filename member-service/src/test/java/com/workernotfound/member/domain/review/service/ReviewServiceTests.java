package com.workernotfound.member.domain.review.service;

import com.workernotfound.member.domain.member.exception.MemberErrorCode;
import com.workernotfound.member.domain.review.dto.request.WriteReviewRequest;
import com.workernotfound.member.domain.review.dto.response.PendingReviewsResponse;
import com.workernotfound.member.domain.review.dto.response.ReceivedReviewsResponse;
import com.workernotfound.member.domain.review.entity.MemberReview;
import com.workernotfound.member.domain.review.entity.enums.ReviewerRole;
import com.workernotfound.member.domain.review.exception.ReviewErrorCode;
import com.workernotfound.member.global.exception.BusinessException;
import com.workernotfound.member.global.exception.ErrorCode;
import com.workernotfound.member.global.exception.GlobalErrorCode;
import com.workernotfound.member.support.IntegrationTestSupport;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewServiceTests extends IntegrationTestSupport {
	private static final AtomicLong IDS = new AtomicLong(8_200_000);
	private static final ZoneId SCHEDULE_ZONE = ZoneId.of("Asia/Seoul");

	@Autowired
	private ReviewCommandService commands;

	@Autowired
	private ReviewQueryService queries;

	@Autowired
	private ReviewableWorkReceiptService receipts;

	@Autowired
	private JdbcTemplate jdbc;

	record Parties(long owner, long worker) {}

	@Test
	void ownerAndWorkerReviewEachOtherAndAveragesAreAggregated() {
		Parties parties = parties();
		long first = completedWork(parties, now().minusHours(1));
		long second = completedWork(parties, now().minusHours(2));

		MemberReview byWorker = commands.write(parties.worker(), new WriteReviewRequest(first, 5, "  친절했어요  "));
		commands.write(parties.owner(), new WriteReviewRequest(first, 3, null));
		commands.write(parties.worker(), new WriteReviewRequest(second, 4, " "));

		assertThat(byWorker.getRevieweeMemberId()).isEqualTo(parties.owner());
		assertThat(byWorker.getReviewerRole()).isEqualTo(ReviewerRole.WORKER);
		assertThat(byWorker.getComment()).isEqualTo("친절했어요");
		ReceivedReviewsResponse owner = queries.received(parties.owner(), 0, 20);
		assertThat(owner.reviewCount()).isEqualTo(2);
		assertThat(owner.averageRating()).isEqualByComparingTo("4.5");
		assertThat(owner.reviews()).extracting(ReceivedReviewsResponse.ReceivedReview::comment)
			.containsExactly(null, "친절했어요");
		ReceivedReviewsResponse worker = queries.received(parties.worker(), 0, 20);
		assertThat(worker.reviewCount()).isEqualTo(1);
		assertThat(worker.averageRating()).isEqualByComparingTo("3.0");
		assertThat(worker.reviews().get(0).reviewerRole()).isEqualTo(ReviewerRole.OWNER);
	}

	@Test
	void sameReviewIsReturnedAgainAndDifferentContentConflicts() {
		Parties parties = parties();
		long work = completedWork(parties, now().minusHours(1));
		MemberReview first = commands.write(parties.worker(), new WriteReviewRequest(work, 4, "좋음"));

		assertThat(commands.write(parties.worker(), new WriteReviewRequest(work, 4, " 좋음 ")).getId())
			.isEqualTo(first.getId());
		assertError(ReviewErrorCode.REVIEW_ALREADY_WRITTEN,
			() -> commands.write(parties.worker(), new WriteReviewRequest(work, 5, "좋음")));
		assertError(ReviewErrorCode.REVIEW_ALREADY_WRITTEN,
			() -> commands.write(parties.worker(), new WriteReviewRequest(work, 4, null)));
		assertThat(queries.received(parties.owner(), 0, 20).reviewCount()).isEqualTo(1);
	}

	@Test
	void onlyParticipantsOfACompletedWorkCanReview() {
		Parties parties = parties();
		long work = completedWork(parties, now().minusHours(1));
		long outsider = member("WORKER");

		assertError(ReviewErrorCode.REVIEW_FORBIDDEN,
			() -> commands.write(outsider, new WriteReviewRequest(work, 5, null)));
		assertError(ReviewErrorCode.REVIEWABLE_WORK_NOT_FOUND,
			() -> commands.write(parties.worker(), new WriteReviewRequest(IDS.incrementAndGet(), 5, null)));
		assertThat(count("select count(*) from member_reviews where work_id = ?", work)).isZero();
	}

	@Test
	void withdrawingReviewerOrRevieweeCannotBeReviewed() {
		Parties parties = parties();
		long work = completedWork(parties, now().minusHours(1));
		jdbc.update("insert into account_gates(member_id, state, command_id) values (?, 'PREPARED', ?)",
			parties.owner(), "00000000-0000-0000-0000-000000000001");

		assertError(GlobalErrorCode.FORBIDDEN, () -> commands.write(parties.worker(), new WriteReviewRequest(work, 5, null)));
		assertError(GlobalErrorCode.FORBIDDEN, () -> commands.write(parties.owner(), new WriteReviewRequest(work, 5, null)));
		assertThat(count("select count(*) from member_reviews where work_id = ?", work)).isZero();
		assertThat(count("select count(*) from member_review_stats where member_id in (?, ?)", parties.owner(),
			parties.worker())).isZero();
	}

	@Test
	void expiredWorkCannotBeReviewedButEarlierReviewIsStillReturned() {
		Parties parties = parties();
		long work = completedWork(parties, now().minusDays(14).minusMinutes(1));
		jdbc.update("insert into member_reviews(work_id, reviewer_member_id, reviewee_member_id, reviewer_role, rating,"
			+ " comment, created_at) values (?, ?, ?, 'OWNER', 2, null, ?)", work, parties.owner(), parties.worker(),
			now().minusDays(10));

		assertError(ReviewErrorCode.REVIEW_PERIOD_EXPIRED,
			() -> commands.write(parties.worker(), new WriteReviewRequest(work, 5, null)));
		assertThat(commands.write(parties.owner(), new WriteReviewRequest(work, 2, null)).getRating()).isEqualTo(2);
	}

	@Test
	void pendingListsOnlyUnreviewedWorksInsideThePeriod() {
		Parties parties = parties();
		long open = completedWork(parties, now().minusHours(1));
		long reviewedByWorker = completedWork(parties, now().minusHours(2));
		completedWork(parties, now().minusDays(15));
		commands.write(parties.worker(), new WriteReviewRequest(reviewedByWorker, 5, null));

		PendingReviewsResponse worker = queries.pending(parties.worker(), 0, 20);
		assertThat(worker.works()).extracting(PendingReviewsResponse.PendingReview::workId).containsExactly(open);
		assertThat(worker.works().get(0).myRole()).isEqualTo(ReviewerRole.WORKER);
		assertThat(worker.works().get(0).revieweeMemberId()).isEqualTo(parties.owner());
		assertThat(worker.works().get(0).writableUntil()).isEqualTo(worker.works().get(0).completedAt().plusDays(14));
		assertThat(queries.pending(parties.owner(), 0, 20).works())
			.extracting(PendingReviewsResponse.PendingReview::workId).containsExactly(open, reviewedByWorker);
	}

	@Test
	void receivedReviewsOfUnknownMemberIsNotFound() {
		assertError(MemberErrorCode.MEMBER_NOT_FOUND, () -> queries.received(IDS.incrementAndGet(), 0, 20));
		Parties parties = parties();
		ReceivedReviewsResponse empty = queries.received(parties.owner(), 0, 20);
		assertThat(empty.reviewCount()).isZero();
		assertThat(empty.averageRating()).isNull();
		assertThat(empty.reviews()).isEmpty();
	}

	@Test
	void concurrentSameReviewCreatesOneReviewAndCountsOnce() throws Exception {
		Parties parties = parties();
		long work = completedWork(parties, now().minusHours(1));
		WriteReviewRequest request = new WriteReviewRequest(work, 5, "최고");

		List<MemberReview> results = parallel(() -> commands.write(parties.worker(), request),
			() -> commands.write(parties.worker(), request));

		assertThat(results.get(0).getId()).isEqualTo(results.get(1).getId());
		assertThat(queries.received(parties.owner(), 0, 20).reviewCount()).isEqualTo(1);
	}

	@Test
	void concurrentReviewsOfOneMemberAreBothCounted() throws Exception {
		Parties parties = parties();
		long first = completedWork(parties, now().minusHours(1));
		long second = completedWork(parties, now().minusHours(2));

		parallel(() -> commands.write(parties.worker(), new WriteReviewRequest(first, 5, null)),
			() -> commands.write(parties.worker(), new WriteReviewRequest(second, 2, null)));

		ReceivedReviewsResponse owner = queries.received(parties.owner(), 0, 20);
		assertThat(owner.reviewCount()).isEqualTo(2);
		assertThat(owner.averageRating()).isEqualByComparingTo("3.5");
	}

	private LocalDateTime now() {
		return LocalDateTime.now(SCHEDULE_ZONE);
	}

	private Parties parties() {
		return new Parties(member("OWNER"), member("WORKER"));
	}

	private long member(String role) {
		long id = IDS.incrementAndGet();
		jdbc.update("insert into members(id, created_at, updated_at, name, email, phone_number, role, status, joined_at)"
				+ " values (?, now(6), now(6), ?, ?, ?, ?, 'ACTIVE', now(6))", id, "리뷰" + id, "review" + id + "@test.com",
			"010" + String.format("%08d", id % 100_000_000), role);
		return id;
	}

	private long completedWork(Parties parties, LocalDateTime completedAt) {
		long workId = IDS.incrementAndGet();
		receipts.receive(new WorkCompletion("event-" + workId, workId, workId + 1, parties.owner(), parties.worker(),
			LocalDate.of(2026, 10, 27), completedAt));
		return workId;
	}

	private long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	private void assertError(ErrorCode code, Runnable action) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
			exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
	}

	private <T> List<T> parallel(Callable<T> first, Callable<T> second) throws Exception {
		var executor = Executors.newFixedThreadPool(2);
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		try {
			Callable<T> a = () -> { ready.countDown(); start.await(10, TimeUnit.SECONDS); return first.call(); };
			Callable<T> b = () -> { ready.countDown(); start.await(10, TimeUnit.SECONDS); return second.call(); };
			Future<T> fa = executor.submit(a);
			Future<T> fb = executor.submit(b);
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			return List.of(fa.get(20, TimeUnit.SECONDS), fb.get(20, TimeUnit.SECONDS));
		} finally {
			start.countDown();
			executor.shutdownNow();
		}
	}
}
