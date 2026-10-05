package com.workernotfound.matching.global.account;

import com.workernotfound.matching.global.exception.BusinessException;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class AccountGateTests extends IntegrationTestSupport {
    @Autowired AccountGateService gates;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Test
    @Transactional
    void ongoingRecordsRejectWithdrawalWithoutInstallingABarrier() {
        jdbc.update("insert into applications(job_post_id,worker_member_id,job_application_admission_id,status,applied_at,created_at,updated_at) values(919202,919203,919204,'APPLIED',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))");
        String key=UUID.randomUUID().toString();
        assertThat(gates.transition(919203L,key,"prepare").state()).isEqualTo("REJECTED");
        assertThat(gates.isActive(919203L)).isTrue();
        assertThat(gates.transition(919203L,key,"prepare").state()).isEqualTo("REJECTED");
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
