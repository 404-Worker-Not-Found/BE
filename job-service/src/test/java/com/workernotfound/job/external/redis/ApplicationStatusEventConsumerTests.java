package com.workernotfound.job.external.redis;

import com.workernotfound.job.domain.job.service.ApplicantCountFindService;
import com.workernotfound.job.domain.job.service.ApplicantCountProjectionService;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.MatchingApplicationEventFixture;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@ExtendWith(OutputCaptureExtension.class)
class ApplicationStatusEventConsumerTests extends IntegrationTestSupport {

    // 다른 테스트 컨텍스트의 레코드와 섞이지 않도록 이 클래스만 쓰는 Stream이다.
    private static final String STREAM = "test:matching:applicant-count-events:" + UUID.randomUUID();
    private static final String GROUP = ApplicationStatusEventConsumer.GROUP;
    private static final Long WORKER_ID = 400L;
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2030, 1, 1, 12, 0);
    private static final AtomicLong JOB_POST_IDS = new AtomicLong(9_161_000);
    private static final AtomicLong APPLICATION_IDS = new AtomicLong(9_161_000);

    @Autowired
    private ApplicationStatusEventConsumer consumer;

    @MockitoSpyBean
    private ApplicantCountProjectionService projectionService;

    @Autowired
    private ApplicantCountFindService findService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    // 소비자 빈을 만들되 스케줄러가 직접 호출과 경쟁하지 않도록 첫 실행을 미룬다.
    @DynamicPropertySource
    static void enableConsumer(DynamicPropertyRegistry registry) {
        registry.add("job.applicant-count.events.enabled", () -> "true");
        registry.add("job.applicant-count.events.stream", () -> STREAM);
        registry.add("job.applicant-count.events.initial-delay", () -> "1h");
    }

    // 그룹을 먼저 만들고 앞선 테스트가 남긴 레코드를 정리한다. 이후 추가한 레코드만 각 테스트가 다룬다.
    @BeforeEach
    void createGroup() {
        consumer.poll();
    }

    @Test
    void appliesFourApplicationEventTypesAndAcknowledgesAfterCommit() {
        long jobPostId = synchronizedEmptyJob();
        long canceled = APPLICATION_IDS.incrementAndGet();
        long selected = APPLICATION_IDS.incrementAndGet();
        long rejected = APPLICATION_IDS.incrementAndGet();
        long applied = APPLICATION_IDS.incrementAndGet();
        List<RecordId> records = List.of(
                publish(event("ApplicationSubmitted", jobPostId, canceled, "APPLIED", 1)),
                publish(event("ApplicationSubmitted", jobPostId, selected, "APPLIED", 1)),
                publish(event("ApplicationSubmitted", jobPostId, rejected, "APPLIED", 1)),
                publish(event("ApplicationSubmitted", jobPostId, applied, "APPLIED", 1)),
                publish(event("ApplicationCanceled", jobPostId, canceled, "CANCELED", 2)),
                publish(event("ApplicationSelected", jobPostId, selected, "SELECTED", 2)),
                publish(event("ApplicationRejected", jobPostId, rejected, "REJECTED", 2)));

        consumer.poll();

        assertThat(findService.findApplicantCount(jobPostId)).isEqualTo(1);
        records.forEach(this::assertNotPending);
    }

    // 이전 연결에서 순서가 바뀌어 취소가 먼저 와도 늦게 온 최초 지원이 집계를 되돌리지 않는다.
    @Test
    void outOfOrderDeliveryConvergesToLatestRevision() {
        long jobPostId = synchronizedEmptyJob();
        long applicationId = APPLICATION_IDS.incrementAndGet();
        Map<String, String> submitted = event("ApplicationSubmitted", jobPostId, applicationId, "APPLIED", 1);
        RecordId canceled = publish(event("ApplicationCanceled", jobPostId, applicationId, "CANCELED", 2));
        RecordId lateSubmitted = publish(submitted);
        RecordId duplicate = publish(submitted);

        consumer.poll();

        assertThat(findService.findApplicantCount(jobPostId)).isZero();
        List.of(canceled, lateSubmitted, duplicate).forEach(this::assertNotPending);
    }

    @Test
    void acknowledgesUnrelatedEventsWithoutApplying() {
        long jobPostId = synchronizedEmptyJob();
        Map<String, String> submitted = event("ApplicationSubmitted", jobPostId, APPLICATION_IDS.incrementAndGet(),
                "APPLIED", 1);
        Map<String, String> otherAggregate = new HashMap<>(submitted);
        otherAggregate.put("aggregateType", "MATCHING");
        Map<String, String> matchConfirmed = new HashMap<>(otherAggregate);
        matchConfirmed.put("eventType", "MatchConfirmed");
        Map<String, String> missingEventType = new HashMap<>(submitted);
        missingEventType.remove("eventType");
        List<RecordId> records = List.of(publish(otherAggregate), publish(matchConfirmed), publish(missingEventType));

        consumer.poll();

        records.forEach(this::assertNotPending);
        assertThat(findService.findApplicantCount(jobPostId)).isZero();
    }

    // 계약을 어긴 이벤트는 반영하지 않고 pending으로 남긴다. 뒤의 정상 이벤트 처리는 막지 않는다.
    @Test
    void contractViolationsStayPendingWithoutBlockingValidEvents(CapturedOutput output) {
        long jobPostId = synchronizedEmptyJob();
        long applicationId = APPLICATION_IDS.incrementAndGet();
        Map<String, String> statusMismatch = event("ApplicationCanceled", jobPostId, applicationId, "APPLIED", 2);
        Map<String, String> revisionMismatch = event("ApplicationSubmitted", jobPostId, applicationId, "APPLIED", 1);
        revisionMismatch.put("revision", "7");
        RecordId badStatus = publish(statusMismatch);
        RecordId badRevision = publish(revisionMismatch);
        RecordId valid = publish(event("ApplicationSubmitted", jobPostId, applicationId, "APPLIED", 1));
        RecordId sameRevisionOtherStatus = publish(event("ApplicationRejected", jobPostId, applicationId, "REJECTED", 1));

        try {
            consumer.poll();
            consumer.poll();

            assertPending(badStatus);
            assertPending(badRevision);
            assertPending(sameRevisionOtherStatus);
            assertNotPending(valid);
            assertThat(findService.findApplicantCount(jobPostId)).isEqualTo(1);
            assertThat(output).contains("반영할 수 없는 지원 상태 이벤트를 pending으로 남깁니다")
                    .contains(sameRevisionOtherStatus.getValue());
        } finally {
            redisTemplate.opsForStream().acknowledge(STREAM, GROUP, badStatus, badRevision, sameRevisionOtherStatus);
        }
    }

    @Test
    void transientFailureLeavesRecordPendingAndRetries() {
        long jobPostId = synchronizedEmptyJob();
        doThrow(new TransientDataAccessResourceException("DB 연결 실패"))
                .doCallRealMethod()
                .when(projectionService).apply(any());
        RecordId record = publish(event("ApplicationSubmitted", jobPostId, APPLICATION_IDS.incrementAndGet(),
                "APPLIED", 1));

        consumer.poll();

        assertThat(findService.findApplicantCount(jobPostId)).isZero();
        assertPending(record);

        consumer.poll();

        assertThat(findService.findApplicantCount(jobPostId)).isEqualTo(1);
        assertNotPending(record);
    }

    // 승인 사용 완료 그룹과 별도 그룹이라 같은 레코드를 각자 받고, 한쪽의 ACK가 다른 쪽에 영향을 주지 않는다.
    @Test
    void usesGroupSeparateFromAdmissionConsumption() {
        assertThat(GROUP).isNotEqualTo(ApplicationSubmittedEventConsumer.GROUP);
        long jobPostId = synchronizedEmptyJob();
        RecordId record = publish(event("ApplicationSubmitted", jobPostId, APPLICATION_IDS.incrementAndGet(),
                "APPLIED", 1));

        consumer.poll();

        assertNotPending(record);
        assertThat(findService.findApplicantCount(jobPostId)).isEqualTo(1);
    }

    private long synchronizedEmptyJob() {
        long jobPostId = JOB_POST_IDS.incrementAndGet();
        projectionService.applySnapshot(jobPostId, List.of(), OCCURRED_AT.minusYears(10));
        return jobPostId;
    }

    private static Map<String, String> event(
            String eventType,
            long jobPostId,
            long applicationId,
            String status,
            long revision
    ) {
        return MatchingApplicationEventFixture.record(UUID.randomUUID().toString(), eventType, applicationId,
                applicationId, jobPostId, WORKER_ID, OCCURRED_AT, status, revision);
    }

    private RecordId publish(Map<String, String> fields) {
        return redisTemplate.opsForStream().add(STREAM, fields);
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
}
