package com.workernotfound.matching.domain.matching.service;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.matching.config.AutoMatchingProperties;
import com.workernotfound.matching.domain.matching.entity.Matching;
import com.workernotfound.matching.domain.matching.entity.MatchingStatusHistory;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingActorType;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingSelectionType;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingStatus;
import com.workernotfound.matching.domain.matching.repository.MatchingCandidateExhaustionRepository;
import com.workernotfound.matching.domain.matching.repository.MatchingRepository;
import com.workernotfound.matching.domain.matching.repository.MatchingStatusHistoryRepository;
import com.workernotfound.matching.domain.score.entity.MatchingScoreBatch;
import com.workernotfound.matching.domain.score.entity.MatchingScoreSnapshot;
import com.workernotfound.matching.domain.score.service.MatchingScoreFindService;
import com.workernotfound.matching.global.account.AccountGateService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지원 마감이 지난 공고의 남은 모집 자리만큼 최신 점수 묶음의 상위 후보에게 제안한다(임시 정책).
 *
 * <p>공고의 대기 지원 행을 모두 잠가 수동 선택·거절·수락 준비와 직렬화한다. 응답 대기 제안과 한 번이라도 확정된 매칭이
 * 자리를 차지하며, 이미 매칭 시도가 있었던 지원(거절·만료 포함)에는 다시 제안하지 않는다. 앞선 제안이 거절·만료된 공고의
 * 제안은 폴백(FALLBACK)으로 기록한다. 점수가 계산되지 않은 지원은 제안하지 않고 다음 점수 묶음을 기다린다.
 */
@Service
@RequiredArgsConstructor
public class AutoMatchingService {

	static final String AUTOMATIC_REASON = "AUTO_CANDIDATE_PROPOSED";
	static final String FALLBACK_REASON = "FALLBACK_CANDIDATE_PROPOSED";
	private static final List<MatchingStatus> FAILED_PROPOSALS = List.of(MatchingStatus.DECLINED, MatchingStatus.EXPIRED);

	private final ApplicationRepository applicationRepository;
	private final MatchingRepository matchingRepository;
	private final MatchingStatusHistoryRepository historyRepository;
	private final MatchingCandidateExhaustionRepository exhaustionRepository;
	private final MatchingScoreFindService scoreFindService;
	private final AccountGateService accountGates;
	private final AutoMatchingProperties properties;
	private final Clock clock;

	@Transactional
	public AutoMatchingResult proposeFor(Long jobPostId) {
		LocalDateTime now = LocalDateTime.now(clock);
		List<Application> waiting = applicationRepository.findAllForUpdate(jobPostId, ApplicationStatus.APPLIED);
		Optional<Application> reference = waiting.stream().filter(this::hasRecruitmentSnapshot).findFirst();
		if (reference.isEmpty() || !hasDeadlinePassed(reference.get(), now) || exhaustionRepository.exists(jobPostId)) {
			return AutoMatchingResult.NOT_DUE;
		}
		int remainingSeats = reference.get().getRecruitCount() - (int) matchingRepository.countOccupiedSeats(jobPostId);
		if (remainingSeats <= 0) {
			return AutoMatchingResult.NO_SEAT;
		}
		Optional<LocalDateTime> responseDeadline = responseDeadline(reference.get(), now);
		if (responseDeadline.isEmpty() || !accountGates.isActive(reference.get().getOwnerMemberId())) {
			return AutoMatchingResult.NOT_DUE;
		}
		Set<Long> tried = new HashSet<>(matchingRepository.findApplicationIdsByJobPostId(jobPostId));
		List<Application> untried = waiting.stream().filter(application -> !tried.contains(application.getId())).toList();
		if (untried.isEmpty()) {
			return recordExhaustion(jobPostId, reference.get(), remainingSeats, now);
		}
		int proposed = propose(jobPostId, untried, remainingSeats, responseDeadline.get(), now);
		return proposed > 0 ? AutoMatchingResult.PROPOSED : AutoMatchingResult.WAITING_FOR_SCORES;
	}

	private int propose(
		Long jobPostId,
		List<Application> untried,
		int remainingSeats,
		LocalDateTime expiresAt,
		LocalDateTime now
	) {
		Optional<MatchingScoreBatch> batch = scoreFindService.findLatestReadyBatch(jobPostId);
		if (batch.isEmpty()) {
			return 0;
		}
		Map<Long, Application> candidates = untried.stream()
			.collect(Collectors.toMap(Application::getId, Function.identity()));
		boolean isFallback = matchingRepository.existsByJobPostIdAndStatusIn(jobPostId, FAILED_PROPOSALS);
		int proposed = 0;
		for (MatchingScoreSnapshot snapshot : scoreFindService.findRankedSnapshots(batch.get().getId())) {
			Application application = candidates.get(snapshot.getApplication().getId());
			if (proposed == remainingSeats) {
				break;
			}
			if (application != null && accountGates.isActive(application.getWorkerMemberId())) {
				saveProposal(application, batch.get(), snapshot, isFallback, expiresAt, now);
				proposed++;
			}
		}
		return proposed;
	}

	private void saveProposal(
		Application application,
		MatchingScoreBatch batch,
		MatchingScoreSnapshot snapshot,
		boolean isFallback,
		LocalDateTime expiresAt,
		LocalDateTime now
	) {
		Matching matching = matchingRepository.saveAndFlush(Matching.builder()
			.application(application)
			.scoreBatch(batch)
			.scoreSnapshot(snapshot)
			.selectionType(isFallback ? MatchingSelectionType.FALLBACK : MatchingSelectionType.AUTOMATIC)
			.selectedAt(now)
			.expiresAt(expiresAt)
			.build());
		historyRepository.saveAndFlush(MatchingStatusHistory.builder()
			.matching(matching)
			.toStatus(MatchingStatus.PENDING)
			.actorType(MatchingActorType.SYSTEM)
			.reasonCode(isFallback ? FALLBACK_REASON : AUTOMATIC_REASON)
			.revision(matching.getRevision())
			.changedAt(now)
			.build());
	}

	// 응답 대기 제안이 남아 있으면 그 결과를 기다린다. 없으면 남은 자리를 후보 소진으로 한 번 기록한다.
	private AutoMatchingResult recordExhaustion(
		Long jobPostId,
		Application reference,
		int remainingSeats,
		LocalDateTime now
	) {
		if (matchingRepository.existsByJobPostIdAndStatus(jobPostId, MatchingStatus.PENDING)) {
			return AutoMatchingResult.WAITING_FOR_RESPONSES;
		}
		exhaustionRepository.record(jobPostId, reference.getOwnerMemberId(), remainingSeats, now);
		return AutoMatchingResult.EXHAUSTED;
	}

	private boolean hasRecruitmentSnapshot(Application application) {
		return application.getApplicationDeadline() != null
			&& application.getRecruitCount() != null
			&& application.getOwnerMemberId() != null;
	}

	private boolean hasDeadlinePassed(Application application, LocalDateTime now) {
		return !now.isBefore(application.getApplicationDeadline());
	}

	// 기본 제한시간을 근무 시작 전 여유 시간까지로 줄인다. 남는 시간이 없으면 제안하지 않는다.
	private Optional<LocalDateTime> responseDeadline(Application application, LocalDateTime now) {
		LocalDateTime expiresAt = now.plus(properties.responseTimeout());
		if (application.getWorkStartsAt() != null) {
			LocalDateTime latest = application.getWorkStartsAt().minus(properties.workStartMargin());
			expiresAt = expiresAt.isAfter(latest) ? latest : expiresAt;
		}
		return expiresAt.isAfter(now) ? Optional.of(expiresAt) : Optional.empty();
	}

	public enum AutoMatchingResult {
		NOT_DUE,
		NO_SEAT,
		PROPOSED,
		WAITING_FOR_SCORES,
		WAITING_FOR_RESPONSES,
		EXHAUSTED
	}
}
