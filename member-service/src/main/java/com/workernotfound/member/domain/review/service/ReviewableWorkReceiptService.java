package com.workernotfound.member.domain.review.service;

import com.workernotfound.member.domain.review.entity.ReviewableWork;
import com.workernotfound.member.domain.review.repository.ReviewableWorkRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReviewableWorkReceiptService {
	private final ReviewableWorkRepository works;
	private final Clock clock;

	public enum Outcome { RECORDED, DUPLICATE, CONFLICT }

	/**
	 * 완료 근무를 리뷰 대상으로 기록한다. 같은 근무의 재전달은 처음 기록을 유지한다. 같은 근무 ID인데 공고·참여자가 다르면
	 * 계약 위반이므로 기록하지 않고 {@link Outcome#CONFLICT}를 돌려준다. 동시에 같은 근무가 기록되면 한쪽은 기본 키 충돌로
	 * 롤백되고, 다음 재전달에서 중복으로 처리된다.
	 */
	@Transactional
	public Outcome receive(WorkCompletion completion) {
		ReviewableWork incoming = ReviewableWork.builder()
			.workId(completion.workId())
			.eventId(completion.eventId())
			.jobPostId(completion.jobPostId())
			.ownerMemberId(completion.ownerMemberId())
			.workerMemberId(completion.workerMemberId())
			.workDate(completion.workDate())
			.completedAt(completion.completedAt())
			.receivedAt(LocalDateTime.now(clock))
			.build();
		Optional<ReviewableWork> existing = works.findById(completion.workId());
		if (existing.isPresent()) return existing.get().describesSameWork(incoming) ? Outcome.DUPLICATE : Outcome.CONFLICT;
		works.saveAndFlush(incoming);
		return Outcome.RECORDED;
	}
}
