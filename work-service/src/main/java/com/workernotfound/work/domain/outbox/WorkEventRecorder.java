package com.workernotfound.work.domain.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.work.domain.work.entity.Work;
import com.workernotfound.work.domain.work.event.WorkLifecycleEvent;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class WorkEventRecorder {
  private final WorkOutboxRepository outbox;
  private final ObjectMapper mapper;

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(Work work, Long actor, String role, LocalDateTime at) {
    var event = WorkLifecycleEvent.from(work, actor, role, at);
    try {
      outbox.append(event, mapper.writeValueAsString(event));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("근무 이벤트 직렬화에 실패했습니다.", exception);
    }
  }
}
