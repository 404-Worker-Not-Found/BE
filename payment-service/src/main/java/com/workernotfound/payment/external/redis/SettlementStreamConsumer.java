package com.workernotfound.payment.external.redis;

import com.workernotfound.payment.domain.settlement.exception.InvalidSettlementEventException;
import com.workernotfound.payment.domain.settlement.exception.SettlementTargetNotReadyException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 정산 이벤트 Stream을 전용 그룹으로 읽는 공통 흐름.
 *
 * <p>ACK 규칙:
 * <ul>
 *   <li>대상이 아닌 이벤트 종류·aggregateType은 바로 ACK한다.</li>
 *   <li>처리 결과(성공, 중복, 영구 거절)는 수신 기록과 함께 커밋된 뒤 ACK한다. 영구 거절은 오류 로그를 남긴다.</li>
 *   <li>해석할 수 없는 envelope, 같은 eventId의 다른 내용, 지원하지 않는 스키마 버전은 기록하지 않고 오류 로그와 함께 pending으로
 *   남긴다. 자동 ACK나 재시도 한도는 두지 않는다.</li>
 *   <li>선행 MatchConfirmed가 아직 기록되지 않은 근무 완료, DB 장애 같은 일시 오류와 ACK 실패도 pending으로 남아 재처리한다.</li>
 * </ul>
 *
 * <p>pending은 매 poll마다 회전 커서로 batch-size만큼 다시 읽은 뒤 새 레코드를 읽는다. 처리되지 않는 레코드가 앞에 쌓여도 뒤 레코드의
 * 복구와 새 레코드 처리를 막지 않는다. 여러 인스턴스는 하나의 논리 소비자를 공유하고 동시 재전달은 MySQL이 직렬화한다.
 */
@Slf4j
abstract class SettlementStreamConsumer {
  private static final String FIRST_ID = "0-0";

  private final StringRedisTemplate redis;
  private final String stream;
  private final String group;
  private final String consumer;
  private final int batchSize;
  private String pendingCursor = FIRST_ID;

  SettlementStreamConsumer(StringRedisTemplate redis, String stream, String group, String consumer,
      int batchSize) {
    this.redis = redis;
    this.stream = stream;
    this.group = group;
    this.consumer = consumer;
    this.batchSize = batchSize;
  }

  abstract boolean isTarget(Map<Object, Object> fields);

  abstract void process(RecordId recordId, Map<Object, Object> fields);

  synchronized void pollStream() {
    try {
      ensureGroup();
      List<MapRecord<String, Object, Object>> pending = read(ReadOffset.from(pendingCursor));
      pendingCursor = pending.isEmpty() ? FIRST_ID : pending.get(pending.size() - 1).getId().getValue();
      pending.forEach(this::handle);
      read(ReadOffset.lastConsumed()).forEach(this::handle);
    } catch (RuntimeException exception) {
      log.warn("정산 이벤트 poll 실패: stream={}, type={}", stream, exception.getClass().getName());
    }
  }

  // 그룹이 없으면 Stream 처음부터 읽도록 만든다. 소비자 연결 전에 발행된 이벤트도 처리한다.
  private void ensureGroup() {
    try {
      redis.execute((RedisCallback<Object>) connection -> connection.execute("XGROUP", bytes("CREATE"),
          bytes(stream), bytes(group), bytes(FIRST_ID), bytes("MKSTREAM")));
    } catch (RuntimeException exception) {
      if (!isBusyGroup(exception)) throw exception;
    }
  }

  private boolean isBusyGroup(Throwable exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause.getMessage() != null && cause.getMessage().startsWith("BUSYGROUP")) return true;
    }
    return false;
  }

  private List<MapRecord<String, Object, Object>> read(ReadOffset offset) {
    List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
        Consumer.from(group, consumer), StreamReadOptions.empty().count(batchSize),
        StreamOffset.create(stream, offset));
    return records == null ? List.of() : records;
  }

  private void handle(MapRecord<String, Object, Object> record) {
    try {
      if (isTarget(record.getValue())) process(record.getId(), record.getValue());
      // 처리 트랜잭션이 커밋된 뒤에만 ACK한다. 다른 서비스 그룹에 영향을 주지 않도록 Stream은 trim하지 않는다.
      redis.opsForStream().acknowledge(stream, group, record.getId());
    } catch (InvalidSettlementEventException exception) {
      log.error("해석할 수 없는 정산 이벤트를 pending으로 남깁니다: stream={}, recordId={}, reason={}", stream,
          record.getId(), exception.getMessage());
    } catch (SettlementTargetNotReadyException exception) {
      log.warn("선행 MatchConfirmed를 기다리도록 pending으로 남깁니다: stream={}, recordId={}", stream,
          record.getId());
    } catch (RuntimeException exception) {
      log.warn("정산 이벤트 처리를 재시도하도록 pending으로 남깁니다: stream={}, recordId={}, type={}", stream,
          record.getId(), exception.getClass().getName());
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
