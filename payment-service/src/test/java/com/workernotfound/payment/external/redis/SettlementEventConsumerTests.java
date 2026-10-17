package com.workernotfound.payment.external.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.payment.domain.payment.dto.request.PaymentLockRequest;
import com.workernotfound.payment.domain.payment.entity.PaymentDeposit;
import com.workernotfound.payment.domain.payment.repository.PaymentDepositRepository;
import com.workernotfound.payment.domain.payment.service.PaymentApplicationService;
import com.workernotfound.payment.domain.settlement.service.MatchConfirmedReceiptService;
import com.workernotfound.payment.support.IntegrationTestSupport;
import com.workernotfound.payment.support.SettlementEventFixture;
import com.workernotfound.payment.support.SettlementEventFixture.Confirmation;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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

@ExtendWith(OutputCaptureExtension.class)
class SettlementEventConsumerTests extends IntegrationTestSupport {
  // 다른 테스트 컨텍스트의 레코드와 섞이지 않도록 이 클래스만 쓰는 Stream이다.
  static final String MATCHING_STREAM = "test:matching:domain-events:" + UUID.randomUUID();
  static final String WORK_STREAM = "test:work:domain-events:" + UUID.randomUUID();
  static final AtomicLong IDS = new AtomicLong(5_000_000);
  static final long OWNER = 20L;
  static final long WORKER = 30L;
  static final LocalDateTime CONFIRMED_AT = LocalDateTime.of(2026, 10, 18, 8, 50, 1, 123_456_789);

  @Autowired MatchConfirmedEventConsumer matchingConsumer;
  @Autowired MatchConfirmedEventParser matchingParser;
  @Autowired MatchConfirmedReceiptService matchingReceipts;
  @Autowired SettlementEventProperties properties;
  @Autowired PaymentApplicationService payments;
  @Autowired PaymentDepositRepository deposits;
  @Autowired StringRedisTemplate redis;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  @DynamicPropertySource
  static void streams(DynamicPropertyRegistry registry) {
    registry.add("payment.settlement-events.matching-stream", () -> MATCHING_STREAM);
    registry.add("payment.settlement-events.work-stream", () -> WORK_STREAM);
  }

  record Locked(long job, long matching, long paymentId, long work) {}

  // 그룹을 먼저 만들고 앞선 테스트가 남긴 레코드를 정리한다. 이후 추가한 레코드만 각 테스트가 다룬다.
  @BeforeEach
  void createGroups() {
    matchingConsumer.poll();
  }

  @Test
  void recordsSettlementTargetFromPublishedMatchConfirmed() {
    Locked locked = locked("100", "60");
    Confirmation event = confirmation(locked, "60");
    RecordId record = publishMatching(event);

    matchingConsumer.poll();

    assertThat(jdbc.queryForMap("SELECT * FROM payment_settlement_targets WHERE matching_id = ?", locked.matching()))
        .containsEntry("work_id", locked.work()).containsEntry("payment_lock_id", locked.paymentId())
        .containsEntry("job_post_id", locked.job()).containsEntry("owner_member_id", OWNER)
        .containsEntry("worker_member_id", WORKER).containsEntry("currency", "KRW")
        .containsEntry("matching_revision", 4L).containsEntry("source_event_id", event.eventId())
        .containsEntry("confirmed_at", SettlementEventFixture.roundToStoredMicros(CONFIRMED_AT));
    assertThat(matchingResult(event.eventId())).isEqualTo("RECORDED");
    assertNotPending(MATCHING_STREAM, MatchConfirmedEventConsumer.GROUP, record);
  }

  @Test
  void duplicateAndRedeliveredConfirmationsKeepFirstTarget() {
    Locked locked = locked("100", "60");
    Confirmation first = confirmation(locked, "60");
    Confirmation redelivered = new Confirmation(UUID.randomUUID().toString(), locked.matching(), 5,
        String.valueOf(locked.work()), String.valueOf(locked.paymentId()), locked.job(), OWNER, WORKER,
        new BigDecimal("60.00"), "KRW", CONFIRMED_AT.plusSeconds(3));
    List<RecordId> records = List.of(publishMatching(first), publishMatching(first), publishMatching(redelivered));

    matchingConsumer.poll();

    records.forEach(record -> assertNotPending(MATCHING_STREAM, MatchConfirmedEventConsumer.GROUP, record));
    assertThat(matchingReceiptResults(locked.matching())).containsExactly("RECORDED", "ALREADY_RECORDED");
    assertThat(jdbc.queryForObject("SELECT source_event_id FROM payment_settlement_targets WHERE matching_id = ?",
        String.class, locked.matching())).isEqualTo(first.eventId());
  }

  // 이전 Saga 시도의 해제 잠금이나 금액·참여자가 다른 확정은 정산 대상이 되지 않는다. 결과를 기록하고 ACK한다.
  @Test
  void recordsConfirmationRejectionsAndAcknowledges(CapturedOutput output) {
    Locked released = locked("100", "60");
    payments.release(UUID.randomUUID().toString(), released.paymentId());
    long current = lock(released.job(), released.matching(), "60");
    Confirmation earlierAttempt = confirmation(released, "60");
    Locked mismatched = locked("100", "60");
    Confirmation otherAmount = confirmation(mismatched, "59");
    Locked malformed = locked("100", "60");
    Confirmation textPayment = new Confirmation(UUID.randomUUID().toString(), malformed.matching(), 4,
        String.valueOf(malformed.work()), "payment-" + malformed.paymentId(), malformed.job(), OWNER, WORKER,
        new BigDecimal("60"), "KRW", CONFIRMED_AT);
    List<RecordId> records = List.of(publishMatching(earlierAttempt), publishMatching(otherAmount),
        publishMatching(textPayment));

    matchingConsumer.poll();

    records.forEach(record -> assertNotPending(MATCHING_STREAM, MatchConfirmedEventConsumer.GROUP, record));
    assertThat(matchingResult(earlierAttempt.eventId())).isEqualTo("LOCK_NOT_FOUND");
    assertThat(matchingResult(otherAmount.eventId())).isEqualTo("LOCK_MISMATCH");
    assertThat(matchingResult(textPayment.eventId())).isEqualTo("INVALID_CONFIRMATION");
    assertThat(targetCount(released.matching()) + targetCount(mismatched.matching())
        + targetCount(malformed.matching())).isZero();
    assertThat(lockStatus(current)).isEqualTo("LOCKED");
    assertThat(output).contains("MatchConfirmed 정산 대상 거절을 기록하고 ACK합니다")
        .contains(earlierAttempt.eventId()).contains("result=LOCK_NOT_FOUND");
  }

  @Test
  void acknowledgesUnrelatedMatchingEventsWithoutProcessing() {
    Locked locked = locked("100", "60");
    Map<String, String> confirmed = matchFields(confirmation(locked, "60"));
    Map<String, String> submitted = new HashMap<>(confirmed);
    submitted.put("eventType", "ApplicationSubmitted");
    submitted.put("aggregateType", "APPLICATION");
    Map<String, String> otherAggregate = new HashMap<>(confirmed);
    otherAggregate.put("aggregateType", "APPLICATION");
    Map<String, String> missingType = new HashMap<>(confirmed);
    missingType.remove("eventType");
    List<RecordId> records = List.of(publish(MATCHING_STREAM, submitted), publish(MATCHING_STREAM, otherAggregate),
        publish(MATCHING_STREAM, missingType));

    matchingConsumer.poll();

    records.forEach(record -> assertNotPending(MATCHING_STREAM, MatchConfirmedEventConsumer.GROUP, record));
    assertThat(targetCount(locked.matching())).isZero();
    assertThat(matchingReceiptResults(locked.matching())).isEmpty();
  }

  // 해석할 수 없는 확정 이벤트는 기록하지 않고 pending으로 남긴다. 뒤의 정상 이벤트 처리는 막지 않는다.
  @Test
  void uninterpretableConfirmationStaysPendingWithoutBlockingValidEvents(CapturedOutput output) {
    Locked locked = locked("100", "60");
    Map<String, String> wrongRevision = matchFields(confirmation(locked, "60"));
    wrongRevision.put("revision", "900");
    Map<String, String> nextVersion = matchFields(confirmation(locked, "60"));
    nextVersion.put("version", "2");
    RecordId badRevision = publish(MATCHING_STREAM, wrongRevision);
    RecordId badVersion = publish(MATCHING_STREAM, nextVersion);
    RecordId valid = publishMatching(confirmation(locked, "60"));

    try {
      matchingConsumer.poll();
      matchingConsumer.poll();

      assertPending(MATCHING_STREAM, MatchConfirmedEventConsumer.GROUP, badRevision);
      assertPending(MATCHING_STREAM, MatchConfirmedEventConsumer.GROUP, badVersion);
      assertNotPending(MATCHING_STREAM, MatchConfirmedEventConsumer.GROUP, valid);
      assertThat(targetCount(locked.matching())).isEqualTo(1);
      assertThat(matchingReceiptResults(locked.matching())).containsExactly("RECORDED");
      assertThat(output).contains("해석할 수 없는 정산 이벤트를 pending으로 남깁니다").contains(badRevision.getValue());
    } finally {
      redis.opsForStream().acknowledge(MATCHING_STREAM, MatchConfirmedEventConsumer.GROUP, badRevision, badVersion);
    }
  }

  @Test
  void sameConfirmationEventIdWithDifferentContentStaysPending() {
    Locked locked = locked("100", "60");
    Confirmation original = confirmation(locked, "60");
    publishMatching(original);
    matchingConsumer.poll();
    Confirmation changed = new Confirmation(original.eventId(), locked.matching(), 4, String.valueOf(locked.work()),
        String.valueOf(locked.paymentId()), locked.job(), OWNER, WORKER, new BigDecimal("60"), "KRW",
        CONFIRMED_AT.plusMinutes(1));
    RecordId conflicting = publishMatching(changed);

    try {
      matchingConsumer.poll();

      assertPending(MATCHING_STREAM, MatchConfirmedEventConsumer.GROUP, conflicting);
      assertThat(matchingReceiptResults(locked.matching())).containsExactly("RECORDED");
    } finally {
      redis.opsForStream().acknowledge(MATCHING_STREAM, MatchConfirmedEventConsumer.GROUP, conflicting);
    }
  }

  // 커밋 뒤 ACK가 실패하고 재시작해도 pending 재수신은 수신 기록으로 중복 판단된다.
  @Test
  void confirmationAcknowledgementFailureRecoversAfterRestart() {
    Locked locked = locked("100", "60");
    RecordId record = publishMatching(confirmation(locked, "60"));

    newMatchingConsumer(templateFailingAcknowledgement()).poll();

    assertThat(targetCount(locked.matching())).isEqualTo(1);
    assertPending(MATCHING_STREAM, MatchConfirmedEventConsumer.GROUP, record);

    newMatchingConsumer(redis).poll();

    assertThat(matchingReceiptResults(locked.matching())).containsExactly("RECORDED");
    assertNotPending(MATCHING_STREAM, MatchConfirmedEventConsumer.GROUP, record);
  }

  MatchConfirmedEventConsumer newMatchingConsumer(StringRedisTemplate template) {
    return new MatchConfirmedEventConsumer(template, matchingParser, matchingReceipts, properties);
  }

  StringRedisTemplate templateFailingAcknowledgement() {
    StringRedisTemplate template = Mockito.spy(redis);
    StreamOperations<String, Object, Object> streams = Mockito.spy(redis.opsForStream());
    doThrow(new RedisConnectionFailureException("ACK 실패"))
        .when(streams).acknowledge(anyString(), anyString(), any(RecordId[].class));
    doReturn(streams).when(template).opsForStream();
    return template;
  }

  // 소비자가 레코드를 받기만 하고 처리·ACK 전에 멈춘 상태를 만든다.
  void deliverWithoutHandling(String stream, String group, String consumer) {
    redis.opsForStream().read(Consumer.from(group, consumer), StreamReadOptions.empty(),
        StreamOffset.create(stream, ReadOffset.lastConsumed()));
  }

  long deposit(String amount) {
    long job = IDS.incrementAndGet();
    // Provider-independent test fixture only; no runtime endpoint fabricates a deposit.
    deposits.saveAndFlush(PaymentDeposit.builder().jobPostId(job).ownerMemberId(OWNER)
        .currency("KRW").depositedAmount(new BigDecimal(amount)).build());
    return job;
  }

  long lock(long job, long matching, String amount) {
    var request = new PaymentLockRequest(matching, job, OWNER, WORKER, new BigDecimal(amount), "KRW");
    return Long.parseLong(payments.lock(UUID.randomUUID().toString(), request).paymentId());
  }

  Locked locked(String deposit, String amount) {
    long job = deposit(deposit);
    long matching = IDS.incrementAndGet();
    return new Locked(job, matching, lock(job, matching, amount), IDS.incrementAndGet());
  }

  Confirmation confirmation(Locked locked, String amount) {
    return new Confirmation(UUID.randomUUID().toString(), locked.matching(), 4, String.valueOf(locked.work()),
        String.valueOf(locked.paymentId()), locked.job(), OWNER, WORKER, new BigDecimal(amount), "KRW",
        CONFIRMED_AT);
  }

  Map<String, String> matchFields(Confirmation event) {
    return SettlementEventFixture.matchConfirmed(mapper, event);
  }

  RecordId publishMatching(Confirmation event) {
    return publish(MATCHING_STREAM, matchFields(event));
  }

  RecordId publish(String stream, Map<String, String> fields) {
    return redis.opsForStream().add(stream, fields);
  }

  void assertPending(String stream, String group, RecordId record) {
    assertThat(pendingCount(stream, group, record)).isEqualTo(1);
  }

  void assertNotPending(String stream, String group, RecordId record) {
    assertThat(pendingCount(stream, group, record)).isZero();
  }

  long pendingCount(String stream, String group, RecordId record) {
    return redis.opsForStream()
        .pending(stream, group, Range.closed(record.getValue(), record.getValue()), 10).size();
  }

  String matchingResult(String eventId) {
    return jdbc.queryForObject("SELECT result FROM payment_matching_event_receipts WHERE event_id = ?",
        String.class, eventId);
  }

  List<String> matchingReceiptResults(long matching) {
    return jdbc.queryForList("SELECT result FROM payment_matching_event_receipts WHERE matching_id = ? ORDER BY id",
        String.class, matching);
  }

  long targetCount(long matching) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlement_targets WHERE matching_id = ?",
        Long.class, matching);
  }

  String lockStatus(long paymentId) {
    return jdbc.queryForObject("SELECT status FROM payment_locks WHERE id = ?", String.class, paymentId);
  }
}
