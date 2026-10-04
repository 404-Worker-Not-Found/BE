package com.workernotfound.job.domain.job.repository;

import com.workernotfound.job.domain.job.entity.RecruitmentCompletionCommand;
import com.workernotfound.job.domain.job.entity.enums.RecruitmentCompletionFailureType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 모집 완료 알림 명령 저장소.
 *
 * <p>실행권 획득과 결과 기록은 모두 기본 키와 상태·실행권 조건을 건 단일 UPDATE로 처리한다. 엔티티를 읽은 뒤 변경 감지로 쓰면
 * 다른 실행자가 그 사이 실행권을 가져간 경우에도 덮어쓰게 되므로 사용하지 않는다.
 */
public interface RecruitmentCompletionCommandRepository extends JpaRepository<RecruitmentCompletionCommand, Long> {

    List<RecruitmentCompletionCommand> findByJobPostId(Long jobPostId);

    Optional<RecruitmentCompletionCommand> findByIdAndLeaseToken(Long id, String leaseToken);

    @Query("""
            select c.id from RecruitmentCompletionCommand c
            where c.status = com.workernotfound.job.domain.job.entity.enums.RecruitmentCompletionCommandStatus.PENDING
              and c.nextAttemptAt <= :now
              and (c.leaseExpiresAt is null or c.leaseExpiresAt <= :now)
            order by c.nextAttemptAt, c.id
            """)
    List<Long> findDueIds(@Param("now") LocalDateTime now, Pageable pageable);

    // 미완료이고 시도 시각이 되었으며 유효한 실행권이 없는 명령만 가져간다. 동시에 실행해도 한 실행자만 1행을 갱신한다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RecruitmentCompletionCommand c
            set c.leaseToken = :leaseToken,
                c.leaseExpiresAt = :leaseExpiresAt,
                c.attemptCount = c.attemptCount + 1,
                c.lastAttemptedAt = :now,
                c.updatedAt = :now
            where c.id = :id
              and c.status = com.workernotfound.job.domain.job.entity.enums.RecruitmentCompletionCommandStatus.PENDING
              and c.nextAttemptAt <= :now
              and (c.leaseExpiresAt is null or c.leaseExpiresAt <= :now)
            """)
    int claim(
            @Param("id") Long id,
            @Param("leaseToken") String leaseToken,
            @Param("now") LocalDateTime now,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt
    );

    // 실행권 토큰이 일치할 때만 기록한다. 실행권을 넘겨받은 새 실행자가 있으면 이전 실행자의 늦은 결과는 0행이 된다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RecruitmentCompletionCommand c
            set c.status = com.workernotfound.job.domain.job.entity.enums.RecruitmentCompletionCommandStatus.SUCCEEDED,
                c.succeededAt = :now,
                c.leaseToken = null,
                c.leaseExpiresAt = null,
                c.updatedAt = :now
            where c.id = :id
              and c.leaseToken = :leaseToken
              and c.status = com.workernotfound.job.domain.job.entity.enums.RecruitmentCompletionCommandStatus.PENDING
            """)
    int markSucceeded(@Param("id") Long id, @Param("leaseToken") String leaseToken, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RecruitmentCompletionCommand c
            set c.nextAttemptAt = :nextAttemptAt,
                c.lastFailureType = :failureType,
                c.lastFailureHttpStatus = :httpStatus,
                c.lastFailureCode = :failureCode,
                c.leaseToken = null,
                c.leaseExpiresAt = null,
                c.updatedAt = :now
            where c.id = :id
              and c.leaseToken = :leaseToken
              and c.status = com.workernotfound.job.domain.job.entity.enums.RecruitmentCompletionCommandStatus.PENDING
            """)
    int markFailed(
            @Param("id") Long id,
            @Param("leaseToken") String leaseToken,
            @Param("failureType") RecruitmentCompletionFailureType failureType,
            @Param("httpStatus") Integer httpStatus,
            @Param("failureCode") String failureCode,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("now") LocalDateTime now
    );
}
