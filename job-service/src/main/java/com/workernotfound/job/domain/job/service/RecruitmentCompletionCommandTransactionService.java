package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.exception.RecruitmentCompletionNotificationException;
import com.workernotfound.job.domain.job.repository.RecruitmentCompletionCommandRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모집 완료 알림 명령의 짧은 상태 변경 트랜잭션.
 *
 * <p>실행권 획득과 결과 기록을 각각 따로 커밋한다. HTTP 호출은 이 트랜잭션들 사이, 트랜잭션 밖에서 실행한다.
 * 결과 기록은 실행권 토큰이 일치할 때만 반영되므로 실행권을 넘겨받은 새 실행자의 상태를 이전 실행자가 덮어쓰지 못한다.
 */
@Service
@RequiredArgsConstructor
public class RecruitmentCompletionCommandTransactionService {

    private final RecruitmentCompletionCommandRepository commandRepository;

    @Transactional
    public Optional<RecruitmentCompletionDispatch> claim(
            Long id,
            String leaseToken,
            LocalDateTime now,
            LocalDateTime leaseExpiresAt
    ) {
        if (commandRepository.claim(id, leaseToken, now, leaseExpiresAt) == 0) {
            return Optional.empty();
        }
        return commandRepository.findByIdAndLeaseToken(id, leaseToken).map(RecruitmentCompletionDispatch::from);
    }

    @Transactional
    public boolean markSucceeded(Long id, String leaseToken, LocalDateTime now) {
        return commandRepository.markSucceeded(id, leaseToken, now) == 1;
    }

    @Transactional
    public boolean markFailed(
            Long id,
            String leaseToken,
            RecruitmentCompletionNotificationException failure,
            LocalDateTime nextAttemptAt,
            LocalDateTime now
    ) {
        return commandRepository.markFailed(
                id,
                leaseToken,
                failure.getFailureType(),
                failure.getHttpStatus(),
                failure.getResponseCode(),
                nextAttemptAt,
                now
        ) == 1;
    }
}
