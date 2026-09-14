package com.workernotfound.work.domain.work.service;

import com.workernotfound.work.domain.work.dto.request.ScheduledWorkRequest;
import com.workernotfound.work.domain.work.dto.response.ScheduledWorkResponse;
import com.workernotfound.work.domain.work.entity.Work;
import com.workernotfound.work.domain.work.exception.WorkErrorCode;
import com.workernotfound.work.domain.work.repository.*;
import com.workernotfound.work.global.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkApplicationService {
  private final WorkRepository works;
  private final WorkCommandRepository commands;

  @Transactional
  public ScheduledWorkResponse create(String key, ScheduledWorkRequest request) {
    var command = lockCommand(key, "CREATE:" + request.toString());
    if (command.workId() != null) return ScheduledWorkResponse.from(command.workId());
    if (commands.lockMatching(request.matchingId()) != null) {
      throw new BusinessException(WorkErrorCode.ACTIVE_WORK_EXISTS);
    }
    Work work =
        works.saveAndFlush(
            Work.builder()
                .matchingId(request.matchingId())
                .jobPostId(request.jobPostId())
                .ownerMemberId(request.ownerMemberId())
                .workerMemberId(request.workerMemberId())
                .paymentId(request.paymentId())
                .workDate(request.workDate())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .build());
    commands.activate(request.matchingId(), work.getId());
    commands.history(work.getId(), null, "SCHEDULED", key);
    commands.complete(key, work.getId());
    return ScheduledWorkResponse.from(work.getId());
  }

  @Transactional
  public void cancel(String key, Long workId) {
    var command = lockCommand(key, "CANCEL:" + workId);
    if (command.workId() != null) return;
    Long matchingId =
        works
            .findMatchingIdById(workId)
            .orElseThrow(() -> new BusinessException(WorkErrorCode.WORK_NOT_FOUND));
    commands.lockMatching(matchingId);
    Work work =
        works
            .findByIdForUpdate(workId)
            .orElseThrow(() -> new BusinessException(WorkErrorCode.WORK_NOT_FOUND));
    if (work.cancel()) {
      commands.history(workId, "SCHEDULED", "CANCELED", key);
      commands.release(work.getMatchingId(), workId);
    }
    commands.complete(key, workId);
  }

  private WorkCommandRepository.Command lockCommand(String key, String payload) {
    String fingerprint = fingerprint(payload);
    var command = commands.lockCommand(key, fingerprint);
    if (!command.fingerprint().equals(fingerprint))
      throw new BusinessException(WorkErrorCode.COMMAND_CONFLICT);
    return command;
  }

  private String fingerprint(String payload) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
    }
  }
}
