package com.workernotfound.member.domain.review.service;

import com.workernotfound.member.domain.member.exception.MemberErrorCode;
import com.workernotfound.member.domain.member.repository.MemberRepository;
import com.workernotfound.member.domain.review.dto.response.PendingReviewsResponse;
import com.workernotfound.member.domain.review.dto.response.PendingReviewsResponse.PendingReview;
import com.workernotfound.member.domain.review.dto.response.ReceivedReviewsResponse;
import com.workernotfound.member.domain.review.dto.response.ReceivedReviewsResponse.ReceivedReview;
import com.workernotfound.member.domain.review.entity.MemberReview;
import com.workernotfound.member.domain.review.entity.ReviewableWork;
import com.workernotfound.member.domain.review.repository.MemberReviewRepository;
import com.workernotfound.member.domain.review.repository.MemberReviewStatsRepository;
import com.workernotfound.member.domain.review.repository.ReviewableWorkRepository;
import com.workernotfound.member.global.exception.BusinessException;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewQueryService {
	private final MemberRepository members;
	private final ReviewableWorkRepository works;
	private final MemberReviewRepository reviews;
	private final MemberReviewStatsRepository stats;
	private final ReviewProperties properties;
	private final Clock clock;

	public ReceivedReviewsResponse received(Long memberId, int page, int size) {
		if (!members.existsById(memberId)) throw new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND);
		MemberReviewStatsRepository.Summary summary = stats.find(memberId);
		Page<MemberReview> result = reviews.findByRevieweeMemberIdOrderByCreatedAtDescIdDesc(memberId,
			PageRequest.of(page, size));
		return new ReceivedReviewsResponse(memberId, summary.reviewCount(), summary.averageRating(),
			result.map(ReceivedReview::from).getContent(), page, size, result.getTotalElements());
	}

	public PendingReviewsResponse pending(Long memberId, int page, int size) {
		// 완료 시각이 이 시각 이하이면 작성 기간이 끝난 근무다.
		LocalDateTime completedAfter = LocalDateTime.now(clock).minus(properties.writePeriod());
		Page<ReviewableWork> result = works.findPending(memberId, completedAfter, PageRequest.of(page, size));
		return new PendingReviewsResponse(result.map(work -> new PendingReview(work.getWorkId(), work.getJobPostId(),
			work.getWorkDate(), work.roleOf(memberId), work.counterpartOf(memberId), work.getCompletedAt(),
			work.writableUntil(properties.writePeriod()))).getContent(), page, size, result.getTotalElements());
	}
}
