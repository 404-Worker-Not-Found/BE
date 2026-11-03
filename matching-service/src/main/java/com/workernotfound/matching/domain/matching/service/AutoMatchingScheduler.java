package com.workernotfound.matching.domain.matching.service;

import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.matching.config.AutoMatchingProperties;
import com.workernotfound.matching.domain.matching.repository.MatchingRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 주기마다 응답 제한시간이 지난 제안을 먼저 만료한 뒤, 지원 마감이 지난 공고에 자동·폴백 제안을 만든다.
 * 공고·제안마다 별도 트랜잭션이며 하나가 실패해도 나머지를 계속 처리하고 다음 주기에 다시 시도한다.
 * 처리 결과와 관계없이 대상 조건에 남는 공고(응답 대기·후보 소진)와 제안(확정 진행 중)이 앞쪽 배치를 계속 차지하지 않도록
 * ID 커서로 이어서 조회하고, 배치가 덜 차면 처음부터 다시 찾는다. 커서는 인스턴스 메모리에만 두며 재시작하면 처음부터 찾는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "matching.auto-matching.enabled", havingValue = "true", matchIfMissing = true)
public class AutoMatchingScheduler {

	private final MatchingRepository matchingRepository;
	private final ApplicationRepository applicationRepository;
	private final MatchingExpiryService expiryService;
	private final AutoMatchingService autoMatchingService;
	private final AutoMatchingProperties properties;
	private final Clock clock;

	private long lastExpiredMatchingId;
	private long lastJobPostId;

	@Scheduled(
		fixedDelayString = "${matching.auto-matching.interval}",
		initialDelayString = "${matching.auto-matching.initial-delay}"
	)
	public synchronized void run() {
		LocalDateTime now = LocalDateTime.now(clock);
		PageRequest batch = PageRequest.of(0, properties.batchSize());
		List<Long> matchingIds = matchingRepository.findExpiredPendingIds(now, lastExpiredMatchingId, batch);
		lastExpiredMatchingId = nextCursor(matchingIds);
		matchingIds.forEach(this::expire);
		List<Long> jobPostIds = applicationRepository.findAutoMatchingJobPostIds(now, lastJobPostId, batch);
		lastJobPostId = nextCursor(jobPostIds);
		jobPostIds.forEach(this::propose);
	}

	// 배치가 덜 차면 끝에 닿은 것으로 보고 다음 실행은 처음부터 찾는다. 처리 성공 여부와 관계없이 커서를 넘긴다.
	private long nextCursor(List<Long> ids) {
		return ids.size() < properties.batchSize() ? 0 : ids.get(ids.size() - 1);
	}

	private void expire(Long matchingId) {
		try {
			expiryService.expire(matchingId);
		} catch (RuntimeException exception) {
			log.warn("매칭 제안 만료에 실패했습니다. matchingId={}", matchingId, exception);
		}
	}

	private void propose(Long jobPostId) {
		try {
			autoMatchingService.proposeFor(jobPostId);
		} catch (RuntimeException exception) {
			log.warn("자동 매칭 제안에 실패했습니다. jobPostId={}", jobPostId, exception);
		}
	}
}
