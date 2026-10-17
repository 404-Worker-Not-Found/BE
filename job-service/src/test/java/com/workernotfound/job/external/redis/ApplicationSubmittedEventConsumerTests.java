package com.workernotfound.job.external.redis;

import com.workernotfound.job.domain.job.entity.JobApplicationAdmission;
import com.workernotfound.job.domain.job.entity.enums.ApplicationAdmissionStatus;
import com.workernotfound.job.domain.job.repository.JobApplicationAdmissionRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.domain.job.service.AdmissionConsumptionCommand;
import com.workernotfound.job.domain.job.service.JobAdmissionEventReceiptService;
import com.workernotfound.job.domain.job.service.JobApplicationAdmissionConsumptionService;
import com.workernotfound.job.support.ApplicationAdmissionFixture;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.MatchingApplicationEventFixture;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

@ExtendWith(OutputCaptureExtension.class)
class ApplicationSubmittedEventConsumerTests extends IntegrationTestSupport {

    // 다른 테스트 컨텍스트의 레코드와 섞이지 않도록 이 클래스만 쓰는 Stream이다.
    private static final String STREAM = "test:matching:domain-events:" + UUID.randomUUID();
    private static final String GROUP = ApplicationSubmittedEventConsumer.GROUP;
    private static final Long WORKER_ID = 300L;
    private static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2030, 1, 1, 12, 0, 0, 123_456_000);
    private static final AtomicLong APPLICATION_IDS = new AtomicLong(800_000);

    @Autowired
    private ApplicationSubmittedEventConsumer consumer;

    @Autowired
    private ApplicationSubmittedEventParser parser;

    @Autowired
    private JobAdmissionEventReceiptService receiptService;

    @Autowired
    private AdmissionEventConsumerProperties properties;

    @MockitoSpyBean
    private JobApplicationAdmissionConsumptionService consumptionService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JobApplicationAdmissionRepository admissionRepository;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 소비자 빈을 만들되 스케줄러가 직접 호출과 경쟁하지 않도록 첫 실행을 미룬다.
    @DynamicPropertySource
    static void enableConsumer(DynamicPropertyRegistry registry) {
        registry.add("job.admission-events.enabled", () -> "true");
        registry.add("job.admission-events.stream", () -> STREAM);
        registry.add("job.admission-events.initial-delay", () -> "1h");
    }

    // 그룹을 먼저 만들고 앞선 테스트가 남긴 레코드를 정리한다. 이후 추가한 레코드만 각 테스트가 다룬다.
    @BeforeEach
    void createGroup() {
        consumer.poll();
    }

    @Test
    void consumesPublishedEventAndAcknowledgesAfterCommit() {
        JobApplicationAdmission admission = saveAdmission();
        LocalDateTime occurredAt = EXPIRES_AT.minusMinutes(5).plusNanos(789);
        RecordId record = publish(submittedFor(admission, occurredAt));

        consumer.poll();

        assertConsumedAt(admission.getId(), MatchingApplicationEventFixture.roundToStoredMicros(occurredAt));
        assertThat(receiptResults(admission.getId())).containsExactly("CONSUMED");
        assertNotPending(record);
    }

    @Test
    void duplicateRecordsOfSameEventChangeAdmissionOnce() {
        JobApplicationAdmission admission = saveAdmission();
        Map<String, String> fields = submittedFor(admission, EXPIRES_AT.minusMinutes(2));
        RecordId first = publish(fields);
        RecordId second = publish(fields);

        consumer.poll();

        assertConsumedAt(admission.getId(), EXPIRES_AT.minusMinutes(2));
        assertThat(receiptResults(admission.getId())).containsExactly("CONSUMED");
        assertNotPending(first);
        assertNotPending(second);
    }

    // 두 인스턴스가 같은 pending 레코드를 동시에 다시 받아도 승인은 한 번만 바뀐다. 진 쪽은 pending으로 남았다가 중복으로 ACK된다.
    @Test
    void concurrentRedeliveryAcrossInstancesChangesAdmissionOnce() throws Exception {
        JobApplicationAdmission admission = saveAdmission();
        RecordId record = publish(submittedFor(admission, EXPIRES_AT.minusMinutes(1)));
        deliverWithoutHandling();
        ApplicationSubmittedEventConsumer otherInstance = newConsumer(redisTemplate);

        CompletableFuture<Void> first = CompletableFuture.runAsync(consumer::poll);
        CompletableFuture<Void> second = CompletableFuture.runAsync(otherInstance::poll);
        CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);
        pollThroughPendingRotation();

        assertConsumedAt(admission.getId(), EXPIRES_AT.minusMinutes(1));
        assertThat(receiptResults(admission.getId())).containsExactly("CONSUMED");
        assertNotPending(record);
    }

    @Test
    void recordsPermanentRejectionsLogsErrorAndAcknowledges(CapturedOutput output) {
        JobApplicationAdmission admission = saveAdmission();
        LocalDateTime occurredAt = EXPIRES_AT.minusMinutes(1);
        Map<String, String> missingAdmission = MatchingApplicationEventFixture.submitted(
                APPLICATION_IDS.incrementAndGet(), null, admission.getJobPostId(), WORKER_ID, occurredAt);
        Map<String, String> otherWorker = MatchingApplicationEventFixture.submitted(
                APPLICATION_IDS.incrementAndGet(), admission.getId(), admission.getJobPostId(), WORKER_ID + 1,
                occurredAt);
        Map<String, String> otherJob = MatchingApplicationEventFixture.submitted(
                APPLICATION_IDS.incrementAndGet(), admission.getId(), admission.getJobPostId() + 1, WORKER_ID,
                occurredAt);
        Map<String, String> unknownAdmission = MatchingApplicationEventFixture.submitted(
                APPLICATION_IDS.incrementAndGet(), 999_999_999L, admission.getJobPostId(), WORKER_ID, occurredAt);
        List<RecordId> records = List.of(publish(missingAdmission), publish(otherWorker), publish(otherJob),
                publish(unknownAdmission));

        consumer.poll();

        assertThat(receiptResult(missingAdmission)).isEqualTo("REJECTED_MISSING_ADMISSION_ID");
        assertThat(receiptResult(otherWorker)).isEqualTo("REJECTED_WORKER_MISMATCH");
        assertThat(receiptResult(otherJob)).isEqualTo("REJECTED_JOB_POST_MISMATCH");
        assertThat(receiptResult(unknownAdmission)).isEqualTo("REJECTED_ADMISSION_NOT_FOUND");
        records.forEach(this::assertNotPending);
        assertUnchanged(admission.getId());
        assertThat(output).contains("ApplicationSubmitted 영구 거절을 기록하고 ACK합니다")
                .contains(otherWorker.get("eventId"))
                .contains("result=REJECTED_WORKER_MISMATCH");
    }

    @Test
    void recordsSubmissionAtExpiryAndAcknowledgesWithoutChange() {
        JobApplicationAdmission admission = saveAdmission();
        RecordId record = publish(submittedFor(admission, EXPIRES_AT));

        consumer.poll();

        assertThat(receiptResults(admission.getId())).containsExactly("SUBMITTED_AFTER_EXPIRY");
        assertUnchanged(admission.getId());
        assertNotPending(record);
    }

    @Test
    void transientFailureLeavesRecordPendingAndRetries() {
        JobApplicationAdmission admission = saveAdmission();
        doThrow(new TransientDataAccessResourceException("DB 연결 실패"))
                .doCallRealMethod()
                .when(consumptionService).consume(commandFor(admission, EXPIRES_AT.minusMinutes(1)));
        RecordId record = publish(submittedFor(admission, EXPIRES_AT.minusMinutes(1)));

        consumer.poll();

        assertUnchanged(admission.getId());
        assertThat(receiptResults(admission.getId())).isEmpty();
        assertPending(record);

        consumer.poll();

        assertConsumedAt(admission.getId(), EXPIRES_AT.minusMinutes(1));
        assertNotPending(record);
    }

    // 승인을 바꾼 뒤 같은 트랜잭션에서 실패하면 승인 변경과 수신 기록이 함께 롤백되고 ACK하지 않는다.
    @Test
    void failureAfterAdmissionChangeRollsBackChangeAndReceipt() {
        JobApplicationAdmission admission = saveAdmission();
        AdmissionConsumptionCommand command = commandFor(admission, EXPIRES_AT.minusMinutes(1));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new TransientDataAccessResourceException("커밋 전 실패");
        }).doCallRealMethod().when(consumptionService).consume(command);
        RecordId record = publish(submittedFor(admission, EXPIRES_AT.minusMinutes(1)));

        consumer.poll();

        assertUnchanged(admission.getId());
        assertThat(receiptResults(admission.getId())).isEmpty();
        assertPending(record);

        consumer.poll();

        assertConsumedAt(admission.getId(), EXPIRES_AT.minusMinutes(1));
        assertThat(receiptResults(admission.getId())).containsExactly("CONSUMED");
        assertNotPending(record);
    }

    // 커밋 뒤 ACK가 실패하고 프로세스가 재시작돼도 pending 재수신은 수신 기록으로 중복 판단되어 승인을 다시 바꾸지 않는다.
    @Test
    void acknowledgementFailureAfterCommitRecoversFromPendingAfterRestart() {
        JobApplicationAdmission admission = saveAdmission();
        RecordId record = publish(submittedFor(admission, EXPIRES_AT.minusMinutes(3)));
        ApplicationSubmittedEventConsumer failingAck = newConsumer(templateFailingAcknowledgement());

        failingAck.poll();

        assertConsumedAt(admission.getId(), EXPIRES_AT.minusMinutes(3));
        assertPending(record);

        ApplicationSubmittedEventConsumer restarted = newConsumer(redisTemplate);
        restarted.poll();

        assertConsumedAt(admission.getId(), EXPIRES_AT.minusMinutes(3));
        assertThat(receiptResults(admission.getId())).containsExactly("CONSUMED");
        assertNotPending(record);
    }

    // 레코드를 받은 직후 처리 전에 프로세스가 멈춘 경우다. 재시작한 소비자가 pending에서 다시 처리한다.
    @Test
    void recoversDeliveryInterruptedBeforeHandling() {
        JobApplicationAdmission admission = saveAdmission();
        RecordId record = publish(submittedFor(admission, EXPIRES_AT.minusMinutes(1)));
        deliverWithoutHandling();
        assertPending(record);

        newConsumer(redisTemplate).poll();

        assertConsumedAt(admission.getId(), EXPIRES_AT.minusMinutes(1));
        assertNotPending(record);
    }

    @Test
    void acknowledgesUnrelatedEventsWithoutProcessing() {
        JobApplicationAdmission admission = saveAdmission();
        Map<String, String> submitted = submittedFor(admission, EXPIRES_AT.minusMinutes(1));
        Map<String, String> canceled = MatchingApplicationEventFixture.record(UUID.randomUUID().toString(),
                "ApplicationCanceled", APPLICATION_IDS.incrementAndGet(), admission.getId(), admission.getJobPostId(),
                WORKER_ID, EXPIRES_AT.minusMinutes(1), "CANCELED");
        Map<String, String> otherAggregate = new HashMap<>(submitted);
        otherAggregate.put("aggregateType", "MATCHING");
        Map<String, String> matchConfirmed = new HashMap<>(otherAggregate);
        matchConfirmed.put("eventType", "MatchConfirmed");
        Map<String, String> missingEventType = new HashMap<>(submitted);
        missingEventType.remove("eventType");
        List<RecordId> records = List.of(publish(canceled), publish(otherAggregate), publish(matchConfirmed),
                publish(missingEventType));

        consumer.poll();

        records.forEach(this::assertNotPending);
        assertUnchanged(admission.getId());
        assertThat(receiptResults(admission.getId())).isEmpty();
    }

    // 해석할 수 없는 대상 이벤트는 기록하지 않고 pending으로 남긴다. 뒤의 정상 이벤트 처리는 막지 않는다.
    @Test
    void uninterpretableEventStaysPendingWithoutBlockingValidEvents(CapturedOutput output) {
        JobApplicationAdmission admission = saveAdmission();
        Map<String, String> malformed = submittedFor(admission, EXPIRES_AT.minusMinutes(1));
        malformed.put("revision", "900");
        RecordId bad = publish(malformed);
        RecordId valid = publish(submittedFor(admission, EXPIRES_AT.minusMinutes(1)));

        try {
            consumer.poll();
            consumer.poll();

            assertPending(bad);
            assertNotPending(valid);
            assertConsumedAt(admission.getId(), EXPIRES_AT.minusMinutes(1));
            assertThat(receiptCount(malformed.get("eventId"))).isZero();
            assertThat(output).contains("해석할 수 없는 ApplicationSubmitted를 pending으로 남깁니다")
                    .contains(bad.getValue());
        } finally {
            redisTemplate.opsForStream().acknowledge(STREAM, GROUP, bad);
        }
    }

    @Test
    void sameEventIdWithDifferentContentStaysPending() {
        JobApplicationAdmission admission = saveAdmission();
        Map<String, String> original = submittedFor(admission, EXPIRES_AT.minusMinutes(2));
        publish(original);
        consumer.poll();
        Map<String, String> changed = MatchingApplicationEventFixture.record(original.get("eventId"),
                "ApplicationSubmitted", Long.valueOf(original.get("aggregateId")), admission.getId(),
                admission.getJobPostId(), WORKER_ID, EXPIRES_AT.minusMinutes(1), "APPLIED");
        RecordId conflicting = publish(changed);

        try {
            consumer.poll();

            assertPending(conflicting);
            assertConsumedAt(admission.getId(), EXPIRES_AT.minusMinutes(2));
            assertThat(receiptCount(original.get("eventId"))).isEqualTo(1);
        } finally {
            redisTemplate.opsForStream().acknowledge(STREAM, GROUP, conflicting);
        }
    }

    private ApplicationSubmittedEventConsumer newConsumer(StringRedisTemplate template) {
        return new ApplicationSubmittedEventConsumer(template, parser, receiptService, properties);
    }

    private StringRedisTemplate templateFailingAcknowledgement() {
        StringRedisTemplate template = Mockito.spy(redisTemplate);
        StreamOperations<String, Object, Object> streams = Mockito.spy(redisTemplate.opsForStream());
        doThrow(new RedisConnectionFailureException("ACK 실패"))
                .when(streams).acknowledge(anyString(), anyString(), any(RecordId[].class));
        doReturn(streams).when(template).opsForStream();
        return template;
    }

    // pending은 회전 커서로 읽으므로 방금 읽은 레코드는 한 번 빈 회차를 지난 뒤 다시 읽힌다.
    private void pollThroughPendingRotation() {
        consumer.poll();
        consumer.poll();
    }

    // 소비자가 레코드를 받기만 하고 처리·ACK 전에 멈춘 상태를 만든다.
    private void deliverWithoutHandling() {
        redisTemplate.opsForStream().read(Consumer.from(GROUP, ApplicationSubmittedEventConsumer.CONSUMER),
                StreamReadOptions.empty(), StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
    }

    private RecordId publish(Map<String, String> fields) {
        return redisTemplate.opsForStream().add(STREAM, fields);
    }

    private Map<String, String> submittedFor(JobApplicationAdmission admission, LocalDateTime occurredAt) {
        return MatchingApplicationEventFixture.submitted(APPLICATION_IDS.incrementAndGet(), admission.getId(),
                admission.getJobPostId(), admission.getWorkerMemberId(), occurredAt);
    }

    private AdmissionConsumptionCommand commandFor(JobApplicationAdmission admission, LocalDateTime occurredAt) {
        return new AdmissionConsumptionCommand(admission.getId(), admission.getJobPostId(),
                admission.getWorkerMemberId(), occurredAt);
    }

    private JobApplicationAdmission saveAdmission() {
        return ApplicationAdmissionFixture.saveReserved(jobPostRepository, admissionRepository, WORKER_ID, EXPIRES_AT);
    }

    private void assertPending(RecordId record) {
        assertThat(pendingCount(record)).isEqualTo(1);
    }

    private void assertNotPending(RecordId record) {
        assertThat(pendingCount(record)).isZero();
    }

    private long pendingCount(RecordId record) {
        return redisTemplate.opsForStream()
                .pending(STREAM, GROUP, Range.closed(record.getValue(), record.getValue()), 10)
                .size();
    }

    private List<String> receiptResults(Long admissionId) {
        return jdbcTemplate.queryForList(
                "SELECT result FROM job_admission_event_receipts WHERE admission_id = ? ORDER BY id",
                String.class, admissionId);
    }

    private String receiptResult(Map<String, String> fields) {
        return jdbcTemplate.queryForObject("SELECT result FROM job_admission_event_receipts WHERE event_id = ?",
                String.class, fields.get("eventId"));
    }

    private long receiptCount(String eventId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM job_admission_event_receipts WHERE event_id = ?",
                Long.class, eventId);
    }

    private void assertConsumedAt(Long admissionId, LocalDateTime expectedConsumedAt) {
        JobApplicationAdmission stored = admissionRepository.findById(admissionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ApplicationAdmissionStatus.CONSUMED);
        assertThat(stored.getConsumedAt()).isEqualTo(expectedConsumedAt);
    }

    private void assertUnchanged(Long admissionId) {
        JobApplicationAdmission stored = admissionRepository.findById(admissionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ApplicationAdmissionStatus.RESERVED);
        assertThat(stored.getConsumedAt()).isNull();
    }
}
