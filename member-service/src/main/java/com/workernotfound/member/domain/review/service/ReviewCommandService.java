package com.workernotfound.member.domain.review.service;

import com.workernotfound.member.domain.review.dto.request.WriteReviewRequest;
import com.workernotfound.member.domain.review.entity.MemberReview;
import com.workernotfound.member.domain.review.entity.ReviewableWork;
import com.workernotfound.member.domain.review.exception.ReviewErrorCode;
import com.workernotfound.member.domain.review.repository.MemberReviewRepository;
import com.workernotfound.member.domain.review.repository.MemberReviewStatsRepository;
import com.workernotfound.member.domain.review.repository.ReviewableWorkRepository;
import com.workernotfound.member.global.account.AccountGateService;
import com.workernotfound.member.global.exception.BusinessException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReviewCommandService {
	private final ReviewableWorkRepository works;
	private final MemberReviewRepository reviews;
	private final MemberReviewStatsRepository stats;
	private final AccountGateService accountGates;
	private final ReviewProperties properties;
	private final Clock clock;

	/**
	 * 근무 참여자가 상대방에게 리뷰를 남긴다. 같은 근무에는 한 번만 쓸 수 있고, 같은 내용의 재요청은 처음 리뷰를 돌려준다.
	 * 잠금 순서는 두 회원의 계정 장벽(ID 오름차순) → 근무 행이다. 같은 근무의 리뷰는 근무 행 잠금으로 직렬화된다.
	 */
	@Transactional
	public MemberReview write(Long reviewerMemberId, WriteReviewRequest request) {
		ReviewableWork target = works.findById(request.workId())
			.orElseThrow(() -> new BusinessException(ReviewErrorCode.REVIEWABLE_WORK_NOT_FOUND));
		if (!target.isParticipant(reviewerMemberId)) throw new BusinessException(ReviewErrorCode.REVIEW_FORBIDDEN);
		accountGates.requireActive(reviewerMemberId, target.counterpartOf(reviewerMemberId));
		ReviewableWork work = works.findByIdForUpdate(request.workId()).orElseThrow();

		int rating = request.rating();
		String comment = request.normalizedComment();
		Optional<MemberReview> existing = reviews.findForUpdate(work.getWorkId(), reviewerMemberId);
		if (existing.isPresent()) {
			// 기간이 지난 뒤의 같은 요청도 처음 결과를 돌려준다.
			if (existing.get().hasSameContent(rating, comment)) return existing.get();
			throw new BusinessException(ReviewErrorCode.REVIEW_ALREADY_WRITTEN);
		}
		LocalDateTime now = LocalDateTime.now(clock);
		if (!now.isBefore(work.writableUntil(properties.writePeriod())))
			throw new BusinessException(ReviewErrorCode.REVIEW_PERIOD_EXPIRED);

		Long revieweeMemberId = work.counterpartOf(reviewerMemberId);
		MemberReview review = reviews.saveAndFlush(MemberReview.builder()
			.workId(work.getWorkId())
			.reviewerMemberId(reviewerMemberId)
			.revieweeMemberId(revieweeMemberId)
			.reviewerRole(work.roleOf(reviewerMemberId))
			.rating(rating)
			.comment(comment)
			.createdAt(now)
			.build());
		stats.add(revieweeMemberId, rating, now);
		return review;
	}
}
