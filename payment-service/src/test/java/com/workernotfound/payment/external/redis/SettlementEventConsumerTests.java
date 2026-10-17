package com.workernotfound.payment.external.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import com.workernotfound.payment.domain.payment.dto.request.PaymentLockRequest;
import com.workernotfound.payment.domain.payment.entity.PaymentDeposit;
import com.workernotfound.payment.domain.payment.repository.PaymentDepositRepository;
import com.workernotfound.payment.domain.payment.service.PaymentApplicationService;
import com.workernotfound.payment.domain.settlement.repository.WorkEventReceiptRepository;
import com.workernotfound.payment.domain.settlement.service.MatchConfirmedReceiptService;
import com.workernotfound.payment.domain.settlement.service.SettlementReservationService;
import com.workernotfound.payment.domain.settlement.service.WorkCompletedReceiptService;
import com.workernotfound.payment.domain.settlement.service.WorkCompletion;
import com.workernotfound.payment.external.job.FundingJobClient;
import com.workernotfound.payment.external.toss.TossGateway;
import com.workernotfound.payment.support.IntegrationTestSupport;
import com.workernotfound.payment.support.SettlementEventFixture;
import com.workernotfound.payment.support.SettlementEventFixture.Confirmation;
import com.workernotfound.payment.support.SettlementEventFixture.WorkEvent;
import java.math.BigDecimal;
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
import org.springframework.dao.DataAccessResourceFailureException;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@ExtendWith(OutputCaptureExtension.class)
class SettlementEventConsumerTests extends IntegrationTestSupport {
  // 다른 테스트 컨텍스트의 레코드와 섞이지 않도록 이 클래스만 쓰는 Stream이다.
  static final String MATCHING_STREAM = "test:matching:domain-events:" + UUID.randomUUID();
  static final String WORK_STREAM = "test:work:domain-events:" + UUID.randomUUID();
  static final AtomicLong IDS = new AtomicLong(5_000_000);
  static final long OWNER = 20L;
  static final long WORKER = 30L;
  static final LocalDateTime CONFIRMED_AT = LocalDateTime.of(2026, 10, 18, 8, 50, 1, 123_456_789);
  // work-service는 발생 시각을 마이크로초로 잘라 발행한다.
  static final LocalDateTime COMPLETED_AT = LocalDateTime.of(2026, 10, 18, 18, 0, 5, 654_321_000);

  @Autowired MatchConfirmedEventConsumer matchingConsumer;
  @Autowired MatchConfirmedEventParser matchingParser;
  @Autowired MatchConfirmedReceiptService matchingReceipts;
  @Autowired WorkCompletedEventConsumer workConsumer;
  @Autowired WorkCompletedEventParser workParser;
  @Autowired WorkCompletedReceiptService workReceipts;
  @Autowired SettlementEventProperties properties;
  @MockitoSpyBean SettlementReservationService reservations;
  @MockitoSpyBean WorkEventReceiptRepository workReceiptRepository;
  // 정산 소비는 PG·job-service 등 외부 HTTP를 호출하지 않아야 한다.
  @MockitoBean TossGateway toss;
  @MockitoBean FundingJobClient fundingJob;
  @Autowired PaymentApplicationService payments;
  @Autowired PaymentDepositRepository deposits;
  @Autowired StringRedisTemplate redis;
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
    workConsumer.poll();
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

  @Test
  void reservesSettlementFromPublishedWorkCompletedWithoutPayout() {
    Locked locked = confirmedTarget("100", "60");
    WorkEvent event = completed(locked);
    RecordId record = publishWork(event);

    workConsumer.poll();

    var settlement = settlement(locked.work());
    assertThat(settlement).containsEntry("payment_lock_id", locked.paymentId()).containsEntry("status", "RESERVED")
        .containsEntry("source_event_id", event.eventId()).containsEntry("work_completed_at", COMPLETED_AT)
        .containsEntry("payable_at", COMPLETED_AT.plusHours(24)).containsEntry("paid_at", null);
    assertThat(workReceipt(event.eventId())).containsEntry("result", "RESERVED")
        .containsEntry("settlement_id", settlement.get("id")).containsEntry("revision", 3L)
        .containsEntry("matching_id", locked.matching());
    assertThat(lockStatus(locked.paymentId())).isEqualTo("TRANSFERRED");
    assertBalance(locked.job(), "0", "60");
    assertNotPending(WORK_STREAM, WorkCompletedEventConsumer.GROUP, record);
    verifyNoInteractions(toss, fundingJob);
  }

  // matching·work Stream은 따로 전달된다. 확정보다 먼저 온 완료는 버리지 않고 pending으로 기다린다.
  @Test
  void completionBeforeConfirmationWaitsInPendingUntilTargetArrives(CapturedOutput output) {
    Locked locked = locked("100", "60");
    RecordId record = publishWork(completed(locked));

    workConsumer.poll();

    assertPending(WORK_STREAM, WorkCompletedEventConsumer.GROUP, record);
    assertThat(workReceiptCount(locked.work())).isZero();
    assertThat(lockStatus(locked.paymentId())).isEqualTo("LOCKED");
    assertThat(output).contains("선행 MatchConfirmed를 기다리도록 pending으로 남깁니다").contains(record.getValue());

    publishMatching(confirmation(locked, "60"));
    matchingConsumer.poll();
    pollWorkThroughPendingRotation();

    assertThat(settlement(locked.work())).containsEntry("status", "RESERVED");
    assertNotPending(WORK_STREAM, WorkCompletedEventConsumer.GROUP, record);
  }

  @Test
  void duplicateRecordsAndDifferentEventIdsCreateOneSettlement() {
    Locked locked = confirmedTarget("100", "60");
    WorkEvent first = completed(locked);
    WorkEvent otherEventId = new WorkEvent(UUID.randomUUID().toString(), "WorkCompleted", 3, "COMPLETED",
        locked.work(), locked.matching(), locked.job(), OWNER, WORKER, COMPLETED_AT);
    List<RecordId> records = List.of(publishWork(first), publishWork(first), publishWork(otherEventId));

    workConsumer.poll();

    records.forEach(record -> assertNotPending(WORK_STREAM, WorkCompletedEventConsumer.GROUP, record));
    assertThat(settlementCount(locked.work())).isEqualTo(1);
    assertThat(workReceiptResults(locked.work())).containsExactly("RESERVED", "ALREADY_RESERVED");
    assertBalance(locked.job(), "0", "60");
  }

  // 두 인스턴스가 같은 pending 레코드를 동시에 다시 받아도 정산은 하나다. 진 쪽은 pending으로 남았다가 중복으로 ACK된다.
  @Test
  void concurrentRedeliveryAcrossInstancesCreatesOneSettlement() throws Exception {
    Locked locked = confirmedTarget("100", "60");
    RecordId record = publishWork(completed(locked));
    deliverWithoutHandling(WORK_STREAM, WorkCompletedEventConsumer.GROUP, WorkCompletedEventConsumer.CONSUMER);
    WorkCompletedEventConsumer otherInstance = newWorkConsumer(redis);

    CompletableFuture<Void> first = CompletableFuture.runAsync(workConsumer::poll);
    CompletableFuture<Void> second = CompletableFuture.runAsync(otherInstance::poll);
    CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);
    pollWorkThroughPendingRotation();

    assertThat(settlementCount(locked.work())).isEqualTo(1);
    assertThat(workReceiptResults(locked.work())).containsExactly("RESERVED");
    assertBalance(locked.job(), "0", "60");
    assertNotPending(WORK_STREAM, WorkCompletedEventConsumer.GROUP, record);
  }

  // eventId가 다른 같은 근무 완료가 동시에 처리돼도 늦은 쪽은 앞선 정산을 보고 ALREADY_RESERVED로 기록한다.
  @Test
  void concurrentCompletionsWithDifferentEventIdsRecordAlreadyReserved() throws Exception {
    Locked locked = confirmedTarget("100", "60");
    WorkCompletion first = completion(locked);
    WorkCompletion second = completion(locked);
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var results = executor.invokeAll(List.<java.util.concurrent.Callable<String>>of(
          () -> workReceipts.receive(first, 3).result(), () -> workReceipts.receive(second, 3).result()));
      List<String> outcomes = new java.util.ArrayList<>();
      for (var result : results) outcomes.add(result.get(30, TimeUnit.SECONDS));

      assertThat(outcomes).containsExactlyInAnyOrder("RESERVED", "ALREADY_RESERVED");
    } finally {
      executor.shutdownNow();
    }
    assertThat(settlementCount(locked.work())).isEqualTo(1);
    assertBalance(locked.job(), "0", "60");
  }

  // 늦게 온 앞 단계 이벤트나 다른 eventId의 완료가 이미 진행된 정산을 과거 상태로 되돌리지 않는다.
  @Test
  void lateOrRepeatedEventsNeverRegressProgressedSettlement() {
    Locked locked = confirmedTarget("100", "60");
    publishWork(completed(locked));
    workConsumer.poll();
    long settlementId = (Long) settlement(locked.work()).get("id");
    jdbc.update("UPDATE payment_settlements SET status = 'PAID', paid_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
        settlementId);
    WorkEvent repeated = new WorkEvent(UUID.randomUUID().toString(), "WorkCompleted", 3, "COMPLETED",
        locked.work(), locked.matching(), locked.job(), OWNER, WORKER, COMPLETED_AT);
    List<RecordId> records = List.of(
        publishWork(lifecycle(locked, "WorkCheckedIn", 1, "CHECKED_IN")),
        publishWork(lifecycle(locked, "WorkStarted", 2, "IN_PROGRESS")),
        publishWork(repeated));

    workConsumer.poll();

    records.forEach(record -> assertNotPending(WORK_STREAM, WorkCompletedEventConsumer.GROUP, record));
    assertThat(settlement(locked.work())).containsEntry("status", "PAID");
    assertThat(workReceiptResults(locked.work())).containsExactly("RESERVED", "ALREADY_RESERVED");
    assertThat(lockStatus(locked.paymentId())).isEqualTo("TRANSFERRED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlement_histories WHERE settlement_id = ?",
        Long.class, settlementId)).isEqualTo(1);
  }

  @Test
  void recordsCompletionRejectionsAndAcknowledges(CapturedOutput output) {
    Locked otherWorker = confirmedTarget("100", "60");
    WorkEvent workerMismatch = new WorkEvent(UUID.randomUUID().toString(), "WorkCompleted", 3, "COMPLETED",
        otherWorker.work(), otherWorker.matching(), otherWorker.job(), OWNER, WORKER + 1, COMPLETED_AT);
    Locked otherWork = confirmedTarget("100", "60");
    WorkEvent unconfirmedWork = new WorkEvent(UUID.randomUUID().toString(), "WorkCompleted", 3, "COMPLETED",
        IDS.incrementAndGet(), otherWork.matching(), otherWork.job(), OWNER, WORKER, COMPLETED_AT);
    Locked rejected = locked("100", "60");
    publishMatching(confirmation(rejected, "59"));
    matchingConsumer.poll();
    WorkEvent rejectedConfirmation = completed(rejected);
    Locked missing = confirmedTarget("100", "60");
    WorkEvent missingMatching = new WorkEvent(UUID.randomUUID().toString(), "WorkCompleted", 3, "COMPLETED",
        missing.work(), null, missing.job(), OWNER, WORKER, COMPLETED_AT);
    List<RecordId> records = List.of(publishWork(workerMismatch), publishWork(unconfirmedWork),
        publishWork(rejectedConfirmation), publishWork(missingMatching));

    workConsumer.poll();

    records.forEach(record -> assertNotPending(WORK_STREAM, WorkCompletedEventConsumer.GROUP, record));
    assertThat(workReceipt(workerMismatch.eventId())).containsEntry("result", "LOCK_MISMATCH");
    assertThat(workReceipt(unconfirmedWork.eventId())).containsEntry("result", "WORK_CONFLICT");
    assertThat(workReceipt(rejectedConfirmation.eventId())).containsEntry("result", "CONFIRMATION_REJECTED");
    assertThat(workReceipt(missingMatching.eventId())).containsEntry("result", "INVALID_COMPLETION")
        .containsEntry("matching_id", null).containsEntry("settlement_id", null);
    for (Locked locked : List.of(otherWorker, otherWork, rejected, missing)) {
      assertThat(lockStatus(locked.paymentId())).isEqualTo("LOCKED");
      assertBalance(locked.job(), "60", "0");
    }
    assertThat(output).contains("WorkCompleted 정산 거절을 기록하고 ACK합니다")
        .contains(workerMismatch.eventId()).contains("result=LOCK_MISMATCH");
  }

  @Test
  void fundingBlockedDepositHoldsSettlementWithoutPayout() {
    Locked locked = confirmedTarget("100", "60");
    jdbc.update("UPDATE payment_deposits SET funding_blocked = TRUE WHERE job_post_id = ?", locked.job());
    publishWork(completed(locked));

    workConsumer.poll();

    assertThat(settlement(locked.work())).containsEntry("status", "ON_HOLD")
        .containsEntry("hold_reason", "FUNDING_BLOCKED").containsEntry("paid_at", null);
    assertBalance(locked.job(), "0", "60");
    verifyNoInteractions(toss, fundingJob);
  }

  @Test
  void transientFailureLeavesCompletionPendingAndRetries() {
    Locked locked = confirmedTarget("100", "60");
    doThrow(new TransientDataAccessResourceException("DB 연결 실패")).doCallRealMethod()
        .when(reservations).reserve(any(WorkCompletion.class));
    RecordId record = publishWork(completed(locked));

    workConsumer.poll();

    assertThat(settlementCount(locked.work())).isZero();
    assertThat(workReceiptCount(locked.work())).isZero();
    assertPending(WORK_STREAM, WorkCompletedEventConsumer.GROUP, record);

    workConsumer.poll();

    assertThat(settlement(locked.work())).containsEntry("status", "RESERVED");
    assertNotPending(WORK_STREAM, WorkCompletedEventConsumer.GROUP, record);
  }

  // 정산 생성·금액 이관 뒤 수신 기록 저장이 실패하면 모두 롤백되고 ACK하지 않는다.
  @Test
  void receiptFailureRollsBackSettlementAndBalance() {
    Locked locked = confirmedTarget("100", "60");
    doThrow(new DataAccessResourceFailureException("수신 기록 저장 실패")).doCallRealMethod()
        .when(workReceiptRepository).save(anyString(), any(), any(), anyLong(), anyString(), anyString(), any());
    RecordId record = publishWork(completed(locked));

    workConsumer.poll();

    assertThat(settlementCount(locked.work())).isZero();
    assertThat(lockStatus(locked.paymentId())).isEqualTo("LOCKED");
    assertBalance(locked.job(), "60", "0");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_status_histories WHERE payment_id = ?"
        + " AND next_status = 'TRANSFERRED'", Long.class, locked.paymentId())).isZero();
    assertPending(WORK_STREAM, WorkCompletedEventConsumer.GROUP, record);

    workConsumer.poll();

    assertThat(workReceiptResults(locked.work())).containsExactly("RESERVED");
    assertBalance(locked.job(), "0", "60");
    assertNotPending(WORK_STREAM, WorkCompletedEventConsumer.GROUP, record);
  }

  // 커밋 뒤 ACK가 실패하고 재시작해도 pending 재수신은 수신 기록으로 중복 판단되어 금액을 다시 옮기지 않는다.
  @Test
  void completionAcknowledgementFailureRecoversAfterRestart() {
    Locked locked = confirmedTarget("100", "60");
    RecordId record = publishWork(completed(locked));

    newWorkConsumer(templateFailingAcknowledgement()).poll();

    assertThat(settlementCount(locked.work())).isEqualTo(1);
    assertPending(WORK_STREAM, WorkCompletedEventConsumer.GROUP, record);

    newWorkConsumer(redis).poll();

    assertThat(settlementCount(locked.work())).isEqualTo(1);
    assertThat(workReceiptResults(locked.work())).containsExactly("RESERVED");
    assertBalance(locked.job(), "0", "60");
    assertNotPending(WORK_STREAM, WorkCompletedEventConsumer.GROUP, record);
  }

  // 레코드를 받은 직후 처리 전에 프로세스가 멈춘 경우다. 재시작한 소비자가 pending에서 다시 처리한다.
  @Test
  void recoversCompletionDeliveredBeforeProcessStopped() {
    Locked locked = confirmedTarget("100", "60");
    RecordId record = publishWork(completed(locked));
    deliverWithoutHandling(WORK_STREAM, WorkCompletedEventConsumer.GROUP, WorkCompletedEventConsumer.CONSUMER);
    assertPending(WORK_STREAM, WorkCompletedEventConsumer.GROUP, record);

    newWorkConsumer(redis).poll();

    assertThat(settlement(locked.work())).containsEntry("status", "RESERVED");
    assertNotPending(WORK_STREAM, WorkCompletedEventConsumer.GROUP, record);
  }

  @Test
  void uninterpretableCompletionStaysPendingWithoutBlockingValidEvents(CapturedOutput output) {
    Locked locked = confirmedTarget("100", "60");
    Map<String, String> wrongStatus = workFields(new WorkEvent(UUID.randomUUID().toString(), "WorkCompleted", 3,
        "IN_PROGRESS", locked.work(), locked.matching(), locked.job(), OWNER, WORKER, COMPLETED_AT));
    Map<String, String> nextVersion = workFields(completed(locked));
    nextVersion.put("version", "2");
    Map<String, String> otherTime = workFields(completed(locked));
    otherTime.put("occurredAt", COMPLETED_AT.plusSeconds(1).toString());
    Map<String, String> unrelatedAggregate = workFields(completed(locked));
    unrelatedAggregate.put("aggregateType", "MATCHING");
    List<RecordId> bad = List.of(publish(WORK_STREAM, wrongStatus), publish(WORK_STREAM, nextVersion),
        publish(WORK_STREAM, otherTime));
    RecordId unrelated = publish(WORK_STREAM, unrelatedAggregate);
    RecordId valid = publishWork(completed(locked));

    try {
      workConsumer.poll();
      workConsumer.poll();

      bad.forEach(record -> assertPending(WORK_STREAM, WorkCompletedEventConsumer.GROUP, record));
      assertNotPending(WORK_STREAM, WorkCompletedEventConsumer.GROUP, unrelated);
      assertNotPending(WORK_STREAM, WorkCompletedEventConsumer.GROUP, valid);
      assertThat(workReceiptResults(locked.work())).containsExactly("RESERVED");
      assertThat(output).contains("해석할 수 없는 정산 이벤트를 pending으로 남깁니다").contains(bad.get(0).getValue());
    } finally {
      redis.opsForStream().acknowledge(WORK_STREAM, WorkCompletedEventConsumer.GROUP, bad.toArray(RecordId[]::new));
    }
  }

  @Test
  void sameCompletionEventIdWithDifferentContentStaysPending() {
    Locked locked = confirmedTarget("100", "60");
    WorkEvent original = completed(locked);
    publishWork(original);
    workConsumer.poll();
    RecordId conflicting = publishWork(new WorkEvent(original.eventId(), "WorkCompleted", 3, "COMPLETED",
        locked.work(), locked.matching(), locked.job(), OWNER, WORKER, COMPLETED_AT.plusMinutes(1)));

    try {
      workConsumer.poll();

      assertPending(WORK_STREAM, WorkCompletedEventConsumer.GROUP, conflicting);
      assertThat(workReceiptResults(locked.work())).containsExactly("RESERVED");
      assertThat(settlement(locked.work())).containsEntry("work_completed_at", COMPLETED_AT);
    } finally {
      redis.opsForStream().acknowledge(WORK_STREAM, WorkCompletedEventConsumer.GROUP, conflicting);
    }
  }

  WorkCompletedEventConsumer newWorkConsumer(StringRedisTemplate template) {
    return new WorkCompletedEventConsumer(template, workParser, workReceipts, properties);
  }

  // pending은 회전 커서로 읽으므로 방금 읽은 레코드는 한 번 빈 회차를 지난 뒤 다시 읽힌다.
  void pollWorkThroughPendingRotation() {
    workConsumer.poll();
    workConsumer.poll();
  }

  Locked confirmedTarget(String deposit, String amount) {
    Locked locked = locked(deposit, amount);
    publishMatching(confirmation(locked, amount));
    matchingConsumer.poll();
    assertThat(targetCount(locked.matching())).isEqualTo(1);
    return locked;
  }

  WorkCompletion completion(Locked locked) {
    return new WorkCompletion(UUID.randomUUID().toString(), locked.work(), locked.matching(), locked.job(), OWNER,
        WORKER, COMPLETED_AT);
  }

  WorkEvent completed(Locked locked) {
    return lifecycle(locked, "WorkCompleted", 3, "COMPLETED");
  }

  // 근무별 revision은 출근 1, 시작 2, 완료 3이다. 완료 시각을 기준으로 앞 단계 시각을 둔다.
  WorkEvent lifecycle(Locked locked, String type, long revision, String status) {
    return new WorkEvent(UUID.randomUUID().toString(), type, revision, status, locked.work(), locked.matching(),
        locked.job(), OWNER, WORKER, COMPLETED_AT.minusHours(3 - revision));
  }

  Map<String, String> workFields(WorkEvent event) {
    return SettlementEventFixture.workEvent(event);
  }

  RecordId publishWork(WorkEvent event) {
    return publish(WORK_STREAM, workFields(event));
  }

  Map<String, Object> settlement(long work) {
    return jdbc.queryForMap("SELECT * FROM payment_settlements WHERE work_id = ?", work);
  }

  long settlementCount(long work) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM payment_settlements WHERE work_id = ?", Long.class, work);
  }

  Map<String, Object> workReceipt(String eventId) {
    return jdbc.queryForMap("SELECT * FROM payment_work_event_receipts WHERE event_id = ?", eventId);
  }

  List<String> workReceiptResults(long work) {
    return jdbc.queryForList("SELECT result FROM payment_work_event_receipts WHERE work_id = ? ORDER BY id",
        String.class, work);
  }

  long workReceiptCount(long work) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM payment_work_event_receipts WHERE work_id = ?",
        Long.class, work);
  }

  void assertBalance(long job, String locked, String reserved) {
    var balance = jdbc.queryForMap("SELECT locked_amount, settlement_reserved_amount, settlement_paid_amount"
        + " FROM payment_deposits WHERE job_post_id = ?", job);
    assertThat((BigDecimal) balance.get("locked_amount")).isEqualByComparingTo(locked);
    assertThat((BigDecimal) balance.get("settlement_reserved_amount")).isEqualByComparingTo(reserved);
    assertThat((BigDecimal) balance.get("settlement_paid_amount")).isEqualByComparingTo("0");
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
    return SettlementEventFixture.matchConfirmed(event);
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
