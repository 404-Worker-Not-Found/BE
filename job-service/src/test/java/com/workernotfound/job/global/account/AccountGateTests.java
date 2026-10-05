package com.workernotfound.job.global.account;

import com.workernotfound.job.support.JobPostFixture;
import org.springframework.jdbc.core.JdbcTemplate;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.domain.job.service.MatchingSeatReservationCommandService;
import com.workernotfound.job.global.exception.BusinessException;
import com.workernotfound.job.support.IntegrationTestSupport;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class AccountGateTests extends IntegrationTestSupport {
    @Autowired MatchingSeatReservationCommandService reservations;
    @Autowired JobPostRepository jobs;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountGateService gates;
    @Autowired PlatformTransactionManager transactionManager;
    @Test
    void expiredReservationReplaysAfterWithdrawalPreparationWhileNewReservationIsBlocked() {
        long worker = 919102L;
        var job = jobs.saveAndFlush(JobPostFixture.open(JobPostFixture.jobPost().build()));
        String key = UUID.randomUUID().toString();
        var first = reservations.reserve(job.getId(), 919102L, 919102L, worker, key);
        jdbc.update("update job_matching_seat_reservations set status='EXPIRED' where id=?", first.getId());
        assertThat(gates.transition(worker, UUID.randomUUID().toString(), "prepare").state()).isEqualTo("PREPARED");
        assertThat(reservations.reserve(job.getId(), 919102L, 919102L, worker, key).getId()).isEqualTo(first.getId());
        assertThatThrownBy(() -> reservations.reserve(job.getId(), 919103L, 919103L, worker, UUID.randomUUID().toString()))
                .isInstanceOf(BusinessException.class);
    }
    @Test
    void releasedAttemptCannotUndoLaterCommittedWithdrawal() {
        long member = 919100L;
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        assertThat(gates.transition(member,first,"prepare").state()).isEqualTo("PREPARED");
        assertThat(gates.transition(member,first,"release").state()).isEqualTo("RELEASED");
        assertThat(gates.transition(member,second,"prepare").state()).isEqualTo("PREPARED");
        assertThat(gates.transition(member,second,"commit").state()).isEqualTo("COMMITTED");
        assertThat(gates.transition(member,first,"release").state()).isEqualTo("RELEASED");
        assertThat(gates.isActive(member)).isFalse();
        assertThat(gates.transition(member,second,"commit").state()).isEqualTo("COMMITTED");
    }
    @Test
    void prepareWaitsForInFlightCreationAndRejectsSubsequentCreation() throws Exception {
        long member = 919101L;
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        var transactions = new TransactionTemplate(transactionManager);
        try {
            Future<?> creation = executor.submit(() -> transactions.executeWithoutResult(status -> {
                gates.requireActive(member); locked.countDown();
                try { if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout"); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            Future<AccountGateService.Result> withdrawal = executor.submit(() -> gates.transition(member,UUID.randomUUID().toString(),"prepare"));
            assertThatThrownBy(() -> withdrawal.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); creation.get(5,TimeUnit.SECONDS);
            assertThat(withdrawal.get(5,TimeUnit.SECONDS).state()).isEqualTo("PREPARED");
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> gates.requireActive(member)))
                .isInstanceOf(BusinessException.class);
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
