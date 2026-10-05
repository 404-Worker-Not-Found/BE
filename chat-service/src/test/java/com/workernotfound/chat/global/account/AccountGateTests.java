package com.workernotfound.chat.global.account;

import com.workernotfound.chat.support.IntegrationTestSupport;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

class AccountGateTests extends IntegrationTestSupport {
    @Autowired AccountGateService gates;
    @Autowired PlatformTransactionManager transactionManager;
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
                .isInstanceOf(com.workernotfound.chat.global.exception.BusinessException.class);
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
