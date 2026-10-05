package com.workernotfound.job.domain.job.repository;

import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;
import com.workernotfound.job.domain.job.entity.enums.PaymentOrderFailureType;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 결제 주문 생성 명령 저장소.
 *
 * <p>실행권 획득과 실패 기록은 기본 키와 상태·실행권 조건을 건 단일 UPDATE로 처리한다. 성공 기록은 공고 연결과 함께 해야 하므로
 * 공고 행과 명령 행을 차례로 잠근 뒤 실행권 토큰을 확인한다.
 */
public interface JobPaymentOrderCommandRepository extends JpaRepository<JobPaymentOrderCommand, Long> {

    List<JobPaymentOrderCommand> findByJobPostIdOrderByIssueSequenceAsc(Long jobPostId);

    Optional<JobPaymentOrderCommand> findFirstByJobPostIdOrderByIssueSequenceDesc(Long jobPostId);

    Optional<JobPaymentOrderCommand> findByIdAndLeaseToken(Long id, String leaseToken);

    @Query("select max(c.issueSequence) from JobPaymentOrderCommand c where c.jobPostId = :jobPostId")
    Optional<Integer> findMaxIssueSequenceByJobPostId(@Param("jobPostId") Long jobPostId);

    /**
     * 공고에 커밋된 최신 발급 순번을 잠금 조회로 읽는다. 호출자는 같은 트랜잭션에서 공고 행 잠금을 먼저 잡고 있어야 한다.
     *
     * <p>MySQL REPEATABLE READ의 일반 조회는 트랜잭션의 첫 일반 조회 시점 스냅샷을 계속 보므로, 공고 잠금을 기다리는 동안 다른
     * 트랜잭션이 커밋한 새 명령을 놓칠 수 있다. 잠금 조회는 항상 최신 커밋 값을 읽는다. 새 명령 발급도 공고 행 잠금을 먼저 잡으므로
     * 공고 잠금을 가진 동안에는 이 값이 바뀌지 않는다.
     */
    @Query(value = """
            select max(issue_sequence) from job_payment_order_commands
            where job_post_id = :jobPostId
            for share
            """, nativeQuery = true)
    Optional<Integer> findCommittedMaxIssueSequenceByJobPostId(@Param("jobPostId") Long jobPostId);

    // 변경되지 않는 공고 ID만 잠그지 않고 읽는다. 공고 행을 명령 행보다 먼저 잠그기 위해 쓴다.
    @Query("select c.jobPostId from JobPaymentOrderCommand c where c.id = :id")
    Optional<Long> findJobPostIdById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from JobPaymentOrderCommand c where c.id = :id")
    Optional<JobPaymentOrderCommand> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select c.id from JobPaymentOrderCommand c
            where c.status = com.workernotfound.job.domain.job.entity.enums.PaymentOrderCommandStatus.PENDING
              and c.nextAttemptAt <= :now
              and (c.leaseExpiresAt is null or c.leaseExpiresAt <= :now)
            order by c.nextAttemptAt, c.id
            """)
    List<Long> findDueIds(@Param("now") LocalDateTime now, Pageable pageable);

    // 미완료이고 시도 시각이 되었으며 유효한 실행권이 없는 명령만 가져간다. 동시에 실행해도 한 실행자만 1행을 갱신한다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update JobPaymentOrderCommand c
            set c.leaseToken = :leaseToken,
                c.leaseExpiresAt = :leaseExpiresAt,
                c.attemptCount = c.attemptCount + 1,
                c.lastAttemptedAt = :now,
                c.updatedAt = :now
            where c.id = :id
              and c.status = com.workernotfound.job.domain.job.entity.enums.PaymentOrderCommandStatus.PENDING
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
            update JobPaymentOrderCommand c
            set c.nextAttemptAt = :nextAttemptAt,
                c.lastFailureType = :failureType,
                c.lastFailureHttpStatus = :httpStatus,
                c.lastFailureCode = :failureCode,
                c.leaseToken = null,
                c.leaseExpiresAt = null,
                c.updatedAt = :now
            where c.id = :id
              and c.leaseToken = :leaseToken
              and c.status = com.workernotfound.job.domain.job.entity.enums.PaymentOrderCommandStatus.PENDING
            """)
    int markFailed(
            @Param("id") Long id,
            @Param("leaseToken") String leaseToken,
            @Param("failureType") PaymentOrderFailureType failureType,
            @Param("httpStatus") Integer httpStatus,
            @Param("failureCode") String failureCode,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("now") LocalDateTime now
    );
}
