package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobApplicationAdmission;
import com.workernotfound.job.domain.job.entity.enums.ApplicationAdmissionStatus;
import com.workernotfound.job.domain.job.repository.JobApplicationAdmissionRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.support.ApplicationAdmissionFixture;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.MutableClock;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지원 접수 승인 만료 배치의 대상·경계, 반복·동시 실행의 1회 변경, 한 승인의 실패와 재시도를 검증한다.
 *
 * <p>대상 조회는 공유 테스트 DB 전체를 보므로 다른 테스트가 남긴 만료 승인도 함께 처리된다. 배치 크기를 넘는 대상이 있어도 확인할 승인이
 * 처리되도록 더 바꿀 승인이 없을 때까지 실행한다.
 */
class JobApplicationAdmissionExpiryTests extends IntegrationTestSupport {

    private static final Long WORKER_ID = 300L;

    // 저장 정밀도(마이크로초)에 맞춘 만료 시각. 다른 테스트가 남긴 먼 미래(2030년) 승인은 대상이 되지 않는다.
    private final LocalDateTime expiresAt = LocalDate.now().plusDays(1).atTime(12, 0, 0, 123_456_000);

    @Autowired
    private JobApplicationAdmissionExpiryReconciler reconciler;

    @Autowired
    private JobApplicationAdmissionExpiryService expiryService;

    @Autowired
    private JobApplicationAdmissionRepository admissionRepository;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        clock.reset();
    }

    @Test
    void expiresReservedAdmissionAtExpiryAndKeepsItBeforeExpiry() {
        JobApplicationAdmission atBoundary = saveAdmission(expiresAt);
        JobApplicationAdmission oneMicroLater = saveAdmission(expiresAt.plus(1, ChronoUnit.MICROS));

        // 1나노초 전에는 후보 조회 인자를 절삭하므로 MySQL 반올림으로 경계가 앞당겨지지 않는다.
        fixAt(expiresAt.minusNanos(1));
        reconcileUntilIdle();
        assertStatus(atBoundary, ApplicationAdmissionStatus.RESERVED);

        fixAt(expiresAt);
        reconcileUntilIdle();

        assertStatus(atBoundary, ApplicationAdmissionStatus.EXPIRED);
        assertStatus(oneMicroLater, ApplicationAdmissionStatus.RESERVED);
    }

    @Test
    void doesNotChangeConsumedOrAlreadyExpiredAdmissions() {
        JobApplicationAdmission consumed = saveAdmission(expiresAt);
        LocalDateTime consumedAt = expiresAt.minusMinutes(1);
        consumed.consume(consumedAt);
        admissionRepository.save(consumed);
        JobApplicationAdmission expired = saveAdmission(expiresAt, ApplicationAdmissionStatus.EXPIRED);
        fixAt(expiresAt.plusMinutes(1));

        assertThat(expiryService.expireIfOverdue(consumed.getId())).isFalse();
        assertThat(expiryService.expireIfOverdue(expired.getId())).isFalse();
        reconcileUntilIdle();

        JobApplicationAdmission storedConsumed = reload(consumed);
        assertThat(storedConsumed.getStatus()).isEqualTo(ApplicationAdmissionStatus.CONSUMED);
        assertThat(storedConsumed.getConsumedAt()).isEqualTo(consumedAt);
        assertStatus(expired, ApplicationAdmissionStatus.EXPIRED);
    }

    @Test
    void repeatedExpiryChangesAdmissionOnce() {
        JobApplicationAdmission admission = saveAdmission(expiresAt);
        fixAt(expiresAt);

        assertThat(expiryService.expireIfOverdue(admission.getId())).isTrue();
        LocalDateTime updatedAt = reload(admission).getUpdatedAt();
        assertThat(expiryService.expireIfOverdue(admission.getId())).isFalse();
        reconcileUntilIdle();

        assertStatus(admission, ApplicationAdmissionStatus.EXPIRED);
        assertThat(reload(admission).getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void missingAdmissionIsSkipped() {
        assertThat(expiryService.expireIfOverdue(Long.MAX_VALUE)).isFalse();
    }

    @Test
    void concurrentExpiryOfSameAdmissionChangesOnce() throws Exception {
        JobApplicationAdmission admission = saveAdmission(expiresAt);
        fixAt(expiresAt);

        List<Boolean> results = runConcurrently(4, () -> expiryService.expireIfOverdue(admission.getId()));

        assertThat(results).containsOnlyOnce(true);
        assertStatus(admission, ApplicationAdmissionStatus.EXPIRED);
    }

    // 여러 인스턴스가 함께 실행되는 경우를 조회 위치가 서로 다른 배치 두 개로 흉내 낸다.
    @Test
    void concurrentReconcilersExpireEveryAdmission() throws Exception {
        List<JobApplicationAdmission> admissions = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            admissions.add(saveAdmission(expiresAt.minusSeconds(index)));
        }
        fixAt(expiresAt);
        List<JobApplicationAdmissionExpiryReconciler> instances = List.of(smallBatchReconciler(2), smallBatchReconciler(2));

        List<Integer> expiredCounts = new ArrayList<>();
        for (Future<Integer> run : submitTogether(instances.stream()
                .<Callable<Integer>>map(instance -> () -> reconcileUntilIdle(instance))
                .toList())) {
            expiredCounts.add(run.get(30, TimeUnit.SECONDS));
        }

        admissions.forEach(admission -> assertStatus(admission, ApplicationAdmissionStatus.EXPIRED));
        assertThat(expiredCounts.stream().mapToInt(Integer::intValue).sum()).isGreaterThanOrEqualTo(admissions.size());
    }

    @Test
    void failingAdmissionDoesNotStopOthersAndIsRetriedLater() throws Exception {
        JobApplicationAdmission failing = saveAdmission(expiresAt);
        JobApplicationAdmission other = saveAdmission(expiresAt);
        fixAt(expiresAt);

        withExpiryUpdateBlocked(List.of(failing), this::reconcileUntilIdle);

        assertStatus(failing, ApplicationAdmissionStatus.RESERVED);
        assertStatus(other, ApplicationAdmissionStatus.EXPIRED);

        reconcileUntilIdle();
        assertStatus(failing, ApplicationAdmissionStatus.EXPIRED);
    }

    // 계속 실패하는 승인이 배치 크기보다 많아도 조회 위치가 넘어가 뒤의 승인을 처리한다.
    @Test
    void failuresFillingBatchDoNotStarveLaterAdmissions() throws Exception {
        List<JobApplicationAdmission> failing = List.of(
                saveAdmission(expiresAt), saveAdmission(expiresAt), saveAdmission(expiresAt));
        JobApplicationAdmission later = saveAdmission(expiresAt);
        fixAt(expiresAt);
        JobApplicationAdmissionExpiryReconciler smallBatch = smallBatchReconciler(2);

        withExpiryUpdateBlocked(failing, () -> {
            for (int run = 0; run < 1_000 && reload(later).getStatus() == ApplicationAdmissionStatus.RESERVED; run++) {
                smallBatch.reconcile();
            }
        });

        assertStatus(later, ApplicationAdmissionStatus.EXPIRED);
        failing.forEach(admission -> assertStatus(admission, ApplicationAdmissionStatus.RESERVED));
    }

    @Test
    void candidateQueryCanUseStatusExpiresIndex() {
        // 저장소 쿼리와 같은 조건이다. 옵티마이저의 실제 선택은 데이터 양에 따라 다르므로 후보 인덱스에 포함되는지만 확인한다.
        String possibleKeys = jdbcTemplate.queryForObject("""
                EXPLAIN SELECT id FROM job_application_admissions
                WHERE status = 'RESERVED' AND expires_at <= NOW(6) AND id > 0
                ORDER BY id LIMIT 100
                """, (row, index) -> row.getString("possible_keys"));

        assertThat(possibleKeys).contains("idx_job_application_admissions_status_expires");
    }

    private JobApplicationAdmissionExpiryReconciler smallBatchReconciler(int batchSize) {
        return new JobApplicationAdmissionExpiryReconciler(admissionRepository, expiryService,
                new JobApplicationAdmissionExpiryProperties(Duration.ofMinutes(1), Duration.ZERO, batchSize), clock);
    }

    private void reconcileUntilIdle() {
        reconcileUntilIdle(reconciler);
    }

    private int reconcileUntilIdle(JobApplicationAdmissionExpiryReconciler instance) {
        int expired = 0;
        int idleRuns = 0;
        for (int run = 0; run < 1_000 && idleRuns < 2; run++) {
            int changed = instance.reconcile();
            expired += changed;
            idleRuns = changed == 0 ? idleRuns + 1 : 0;
        }
        return expired;
    }

    private <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        List<Callable<T>> tasks = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            tasks.add(task);
        }
        List<T> results = new ArrayList<>();
        for (Future<T> future : submitTogether(tasks)) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    private <T> List<Future<T>> submitTogether(List<Callable<T>> tasks) {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(executor.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        return futures;
    }

    // 지정한 승인의 EXPIRED 변경만 DB에서 실패시킨다. 다른 승인과 다른 변경에는 영향을 주지 않는다.
    private void withExpiryUpdateBlocked(List<JobApplicationAdmission> admissions, Runnable action) throws Exception {
        String trigger = "fail_admission_expiry_" + admissions.get(0).getId();
        String ids = String.join(",", admissions.stream().map(admission -> admission.getId().toString()).toList());
        executeAsRoot("""
                CREATE TRIGGER %s BEFORE UPDATE ON job_application_admissions FOR EACH ROW
                BEGIN
                    IF NEW.id IN (%s) AND NEW.status = 'EXPIRED' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'admission expiry blocked by test';
                    END IF;
                END
                """.formatted(trigger, ids));
        try {
            action.run();
        } finally {
            executeAsRoot("DROP TRIGGER " + trigger);
        }
    }

    private void executeAsRoot(String sql) throws Exception {
        try (Connection connection = openLockObserverConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void fixAt(LocalDateTime time) {
        clock.fixAt(time.atZone(clock.getZone()).toInstant());
    }

    private JobApplicationAdmission saveAdmission(LocalDateTime admissionExpiresAt) {
        return ApplicationAdmissionFixture.saveReserved(
                jobPostRepository, admissionRepository, WORKER_ID, admissionExpiresAt);
    }

    private JobApplicationAdmission saveAdmission(LocalDateTime admissionExpiresAt, ApplicationAdmissionStatus status) {
        JobApplicationAdmission admission = saveAdmission(admissionExpiresAt);
        ReflectionTestUtils.setField(admission, "status", status);
        return admissionRepository.save(admission);
    }

    private JobApplicationAdmission reload(JobApplicationAdmission admission) {
        return admissionRepository.findById(admission.getId()).orElseThrow();
    }

    private void assertStatus(JobApplicationAdmission admission, ApplicationAdmissionStatus expected) {
        JobApplicationAdmission stored = reload(admission);
        assertThat(stored.getStatus()).isEqualTo(expected);
        if (expected != ApplicationAdmissionStatus.CONSUMED) {
            assertThat(stored.getConsumedAt()).isNull();
        }
    }
}
