package com.workernotfound.member.domain.review.service;

import com.workernotfound.member.domain.review.repository.MemberNoShowWorkRepository;
import com.workernotfound.member.domain.review.repository.MemberReviewStatsRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NoShowPenaltyService {
	private final MemberNoShowWorkRepository noShows;
	private final MemberReviewStatsRepository stats;
	private final TrustScoreRecorder trustScores;
	private final Clock clock;

	public enum Outcome { RECORDED, DUPLICATE, CONFLICT }

	/**
	 * 노쇼 근무를 기록하고 근로자의 노쇼 횟수를 1 올린 뒤 같은 트랜잭션에서 신뢰 점수를 다시 계산한다.
	 * 같은 근무의 재전달은 처음 기록을 유지하고 다시 감점하지 않는다. 같은 근무 ID인데 공고·참여자가 다르면 계약 위반이므로
	 * 기록하지 않고 {@link Outcome#CONFLICT}를 돌려준다. 잠금 순서는 노쇼 근무 행 → 신뢰 점수 입력 집계 행 → 신뢰 점수 행이다.
	 */
	@Transactional
	public Outcome receive(WorkNoShow noShow) {
		Optional<WorkNoShow> existing = noShows.find(noShow.workId());
		if (existing.isPresent()) return existing.get().describesSameWork(noShow) ? Outcome.DUPLICATE : Outcome.CONFLICT;
		LocalDateTime now = LocalDateTime.now(clock);
		noShows.insert(noShow, now);
		stats.addNoShow(noShow.workerMemberId(), now);
		trustScores.record(noShow.workerMemberId(), now);
		return Outcome.RECORDED;
	}
}
