package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.repository.JobMatchingSeatReservationRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 만료된 RESERVED 예약을 EXPIRED로 회수한다.
 *
 * <p>한 번에 최대 배치 크기만큼의 공고를 고르고, 공고마다 별도 트랜잭션에서 공고 행을 잠근 뒤 회수한다.
 * 한 트랜잭션이 여러 공고를 오래 잠그지 않는다. 여러 인스턴스가 동시에 실행해도 공고 잠금과 상태 조건으로 중복 회수하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchingSeatReservationExpiryService {

    private final JobMatchingSeatReservationRepository reservationRepository;
    private final MatchingSeatReservationCommandService commandService;
    private final MatchingSeatReservationProperties properties;
    private final Clock clock;

    public int expireOverdueReservations() {
        List<Long> jobPostIds = reservationRepository.findJobPostIdsWithOverdueReservation(
                LocalDateTime.now(clock),
                PageRequest.of(0, properties.expirySweepBatchSize())
        );
        int expired = 0;
        for (Long jobPostId : jobPostIds) {
            expired += expireJobPost(jobPostId);
        }
        return expired;
    }

    // 한 공고의 실패가 다른 공고 회수를 막지 않게 한다. 남은 예약은 다음 실행이나 새 예약 요청에서 다시 회수된다.
    private int expireJobPost(Long jobPostId) {
        try {
            return commandService.expireOverdue(jobPostId);
        } catch (RuntimeException exception) {
            log.warn("모집 자리 예약 만료 회수 실패: jobPostId={}, type={}",
                    jobPostId, exception.getClass().getName());
            return 0;
        }
    }
}
