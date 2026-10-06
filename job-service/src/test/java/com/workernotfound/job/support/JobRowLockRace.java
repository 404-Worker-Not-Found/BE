package com.workernotfound.job.support;

import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 같은 공고 행 잠금을 쓰는 두 명령의 실행 순서를 고정한다.
 *
 * <p>첫 명령을 쓰기 후 커밋 직전에 멈추고, 경쟁 명령이 같은 공고 행 잠금을 기다리는지 MySQL 잠금 대기로 확인한 뒤 첫 명령을 커밋한다.
 * 두 명령이 공고 행 잠금으로 직렬화되므로 결과는 커밋 순서로만 결정된다. 한 번의 경쟁마다 새 인스턴스를 쓴다.
 */
public final class JobRowLockRace {

    // MySQL은 잠금이 걸린 레코드의 페이지를 바로 읽을 수 없으면 LOCK_DATA를 NULL로 보고한다. 첫 트랜잭션이 잠근 공고 행은 하나뿐이므로
    // 대기자·차단자 연결과 테이블·인덱스·모드가 맞으면 NULL도 이 공고 행의 대기로 인정한다.
    private static final String JOB_LOCK_WAIT_QUERY = """
            SELECT 1
            FROM performance_schema.data_lock_waits waits
            JOIN performance_schema.data_locks requested
              ON requested.ENGINE = waits.ENGINE
             AND requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
            JOIN performance_schema.threads requester
              ON requester.THREAD_ID = waits.REQUESTING_THREAD_ID
            JOIN performance_schema.threads blocker
              ON blocker.THREAD_ID = waits.BLOCKING_THREAD_ID
            WHERE requester.PROCESSLIST_ID = ?
              AND blocker.PROCESSLIST_ID = ?
              AND requested.OBJECT_SCHEMA = ?
              AND requested.OBJECT_NAME = 'job_posts'
              AND requested.INDEX_NAME = 'PRIMARY'
              AND requested.LOCK_TYPE = 'RECORD'
              AND requested.LOCK_MODE LIKE 'X%'
              AND requested.LOCK_STATUS = 'WAITING'
              AND (requested.LOCK_DATA = ? OR requested.LOCK_DATA IS NULL)
            """;

    private final PlatformTransactionManager transactionManager;
    private final EntityManager entityManager;
    private final ExecutorService executor;
    private final CountDownLatch firstWritten = new CountDownLatch(1);
    private final CountDownLatch releaseFirst = new CountDownLatch(1);
    private final CompletableFuture<Long> contenderConnectionId = new CompletableFuture<>();
    private volatile long firstConnectionId;

    public JobRowLockRace(
            PlatformTransactionManager transactionManager,
            EntityManager entityManager,
            ExecutorService executor
    ) {
        this.transactionManager = transactionManager;
        this.entityManager = entityManager;
        this.executor = executor;
    }

    /**
     * 첫 명령을 커밋한 결과와, 그 뒤에 이어서 처리된 경쟁 명령의 결과를 돌려준다. 경쟁 명령의 예외는 반환된 Future에서 확인한다.
     */
    public <F, C> Outcome<F, C> run(Long jobPostId, Supplier<F> first, Supplier<C> contender) throws Exception {
        Future<F> firstResult = executor.submit(() -> holdBeforeCommit(first));
        try {
            awaitLatch(firstWritten);
            Future<C> contending = executor.submit(() -> runContender(contender));
            assertWaitingForJobLock(contending, jobPostId);
            releaseFirst.countDown();
            F committed = firstResult.get(10, TimeUnit.SECONDS);
            try {
                contending.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException ignored) {
                // 경쟁 명령의 실패는 호출자가 Future로 검증한다.
            }
            return new Outcome<>(committed, contending);
        } finally {
            releaseFirst.countDown();
        }
    }

    private <T> T holdBeforeCommit(Supplier<T> operation) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            firstConnectionId = currentConnectionId();
            T result = operation.get();
            // 지연 쓰기 엔티티 변경도 잠금과 함께 DB에 반영한 뒤 멈춘다.
            entityManager.flush();
            firstWritten.countDown();
            awaitLatch(releaseFirst);
            return result;
        });
    }

    private <T> T runContender(Supplier<T> operation) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            contenderConnectionId.complete(currentConnectionId());
            return operation.get();
        });
    }

    private long currentConnectionId() {
        return ((Number) entityManager.createNativeQuery("SELECT CONNECTION_ID()").getSingleResult()).longValue();
    }

    private void assertWaitingForJobLock(Future<?> contender, Long jobPostId) throws Exception {
        long connectionId = contenderConnectionId.get(10, TimeUnit.SECONDS);
        try (Connection observer = IntegrationTestSupport.openLockObserverConnection();
                PreparedStatement query = observer.prepareStatement(JOB_LOCK_WAIT_QUERY)) {
            query.setLong(1, connectionId);
            query.setLong(2, firstConnectionId);
            query.setString(3, observer.getCatalog());
            query.setString(4, jobPostId.toString());
            query.setQueryTimeout(2);
            await().alias("경쟁 트랜잭션이 첫 트랜잭션의 공고 행 잠금을 기다려야 한다")
                    .pollInSameThread().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(5))
                    .until(() -> {
                        assertThat(contender.isDone()).as("잠금 관찰 전에 경쟁 요청이 끝나면 안 된다").isFalse();
                        try (ResultSet waiting = query.executeQuery()) {
                            return waiting.next();
                        }
                    });
        }
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("경쟁 테스트 대기가 중단되었습니다.", exception);
        }
    }

    public record Outcome<F, C>(F first, Future<C> contender) {
    }
}
