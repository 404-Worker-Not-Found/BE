package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.dto.request.CreateJobRequest;
import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.PaymentOrderCommandStatus;
import com.workernotfound.job.domain.job.repository.JobPaymentOrderCommandRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 공고 생성과 결제 주문 생성 명령 저장은 한 로컬 트랜잭션이다. 명령은 발급 당시 스냅샷을 바꾸지 않는다.
class JobPaymentOrderCommandIssueTests extends IntegrationTestSupport {

    private static final AtomicLong OWNER_SEQUENCE = new AtomicLong(920_000);

    @Autowired
    private JobCommandService jobCommandService;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private JobPaymentOrderCommandRepository commandRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void creationStoresPendingCommandWithPaymentSnapshot() {
        long ownerId = OWNER_SEQUENCE.incrementAndGet();

        Long jobPostId = jobCommandService.create(ownerId, request(10_320, 500, 2));

        JobPost jobPost = jobPostRepository.findById(jobPostId).orElseThrow();
        assertThat(jobPost.getStatus()).isEqualTo(JobStatus.PAYMENT_PENDING);
        List<JobPaymentOrderCommand> commands = commandRepository.findByJobPostIdOrderByIssueSequenceAsc(jobPostId);
        assertThat(commands).singleElement().satisfies(command -> {
            assertThat(command.getIssueSequence()).isEqualTo(1);
            assertThat(command.getJobVersion()).isEqualTo(jobPost.getVersion()).isEqualTo(1L);
            assertThat(command.getOwnerMemberId()).isEqualTo(ownerId);
            // 9시간 × (10,320 + 500)원 = 97,380원, 2명
            assertThat(command.getAmount()).isEqualTo(194_760L);
            assertThat(command.getCurrency()).isEqualTo("KRW");
            assertThat(UUID.fromString(command.getIdempotencyKey())).isNotNull();
            assertThat(command.getStatus()).isEqualTo(PaymentOrderCommandStatus.PENDING);
            assertThat(command.getOrderId()).isNull();
            assertThat(command.getAttemptCount()).isZero();
        });
        assertThat(jobPost.getPaymentOrderId()).isNull();
    }

    @Test
    void commandInsertFailureRollsBackJobCreation() throws Exception {
        long ownerId = OWNER_SEQUENCE.incrementAndGet();
        String trigger = "fail_payment_order_command_" + ownerId;
        // 이 점주의 명령 INSERT만 DB에서 실패시킨다. 다른 테스트의 공고에는 영향을 주지 않는다.
        executeAsRoot("""
                CREATE TRIGGER %s BEFORE INSERT ON job_payment_order_commands FOR EACH ROW
                BEGIN
                    IF NEW.owner_member_id = %d THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'payment order command insert blocked by test';
                    END IF;
                END
                """.formatted(trigger, ownerId));
        try {
            assertThatThrownBy(() -> jobCommandService.create(ownerId, request(10_320, null, 1)))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            executeAsRoot("DROP TRIGGER " + trigger);
        }

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from job_posts where owner_id = ?", Integer.class, ownerId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from job_payment_order_commands where owner_member_id = ?", Integer.class, ownerId))
                .isZero();
    }

    @Test
    void snapshotColumnsAreNotUpdated() {
        Long jobPostId = jobCommandService.create(OWNER_SEQUENCE.incrementAndGet(), request(10_320, null, 1));
        JobPaymentOrderCommand issued = commandRepository.findByJobPostIdOrderByIssueSequenceAsc(jobPostId).get(0);

        transactionTemplate.executeWithoutResult(status -> {
            JobPaymentOrderCommand loaded = commandRepository.findById(issued.getId()).orElseThrow();
            ReflectionTestUtils.setField(loaded, "jobVersion", 99L);
            ReflectionTestUtils.setField(loaded, "amount", 1_000L);
            ReflectionTestUtils.setField(loaded, "idempotencyKey", UUID.randomUUID().toString());
            ReflectionTestUtils.setField(loaded, "attemptCount", 1);
        });

        JobPaymentOrderCommand reloaded = commandRepository.findById(issued.getId()).orElseThrow();
        assertThat(reloaded.getJobVersion()).isEqualTo(issued.getJobVersion());
        assertThat(reloaded.getAmount()).isEqualTo(issued.getAmount());
        assertThat(reloaded.getIdempotencyKey()).isEqualTo(issued.getIdempotencyKey());
        // 스냅샷이 아닌 처리 상태 컬럼은 갱신된다(대조군).
        assertThat(reloaded.getAttemptCount()).isOne();
    }

    @Test
    void rejectsDuplicateIssueSequenceAndIdempotencyKey() {
        Long jobPostId = jobCommandService.create(OWNER_SEQUENCE.incrementAndGet(), request(10_320, null, 1));
        JobPaymentOrderCommand issued = commandRepository.findByJobPostIdOrderByIssueSequenceAsc(jobPostId).get(0);

        assertThatThrownBy(() -> commandRepository.saveAndFlush(copyOf(issued, 1, UUID.randomUUID().toString())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> commandRepository.saveAndFlush(copyOf(issued, 2, issued.getIdempotencyKey())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private JobPaymentOrderCommand copyOf(JobPaymentOrderCommand command, int issueSequence, String key) {
        return JobPaymentOrderCommand.builder()
                .jobPostId(command.getJobPostId())
                .issueSequence(issueSequence)
                .idempotencyKey(key)
                .jobVersion(command.getJobVersion())
                .ownerMemberId(command.getOwnerMemberId())
                .amount(command.getAmount())
                .currency(command.getCurrency())
                .nextAttemptAt(LocalDateTime.now())
                .build();
    }

    private CreateJobRequest request(int baseHourlyWage, Integer extraWage, int recruitCount) {
        LocalDate workDate = LocalDate.now().plusDays(2);
        return new CreateJobRequest(
                1L, 1L, "테스트 상점", "서울시 마포구", "결제 명령 공고", "설명",
                workDate, LocalTime.of(9, 0), LocalTime.of(18, 0), false, baseHourlyWage, extraWage, recruitCount,
                new BigDecimal("37.5665000"), new BigDecimal("126.9780000"), "MEDIUM",
                LocalDateTime.of(workDate.minusDays(1), LocalTime.NOON));
    }

    private void executeAsRoot(String sql) throws Exception {
        try (Connection connection = openLockObserverConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
