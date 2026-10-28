package com.workernotfound.work.domain.work.service;

import com.workernotfound.work.domain.work.dto.request.CancellationRequest;
import com.workernotfound.work.domain.work.dto.response.WorkCancellationResponse;
import com.workernotfound.work.domain.work.entity.WorkCancellationRequest;
import com.workernotfound.work.domain.work.entity.enums.WorkCancellationStatus;
import com.workernotfound.work.domain.work.exception.WorkErrorCode;
import com.workernotfound.work.global.exception.BusinessException;
import com.workernotfound.work.global.security.AuthenticatedMember;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WorkCancellationApplicationService {
  private final WorkCancellationCommandService commands;
  private final WorkCancellationFindService queries;

  public WorkCancellationResponse request(AuthenticatedMember member, Long workId, String key, CancellationRequest request) {
    return WorkCancellationResponse.from(commands.request(member, workId, key, request.reason()));
  }

  public WorkCancellationResponse respond(AuthenticatedMember member, Long workId, Long requestId, boolean approve) {
    return response(commands.respond(member, workId, requestId, approve));
  }

  public WorkCancellationResponse withdraw(AuthenticatedMember member, Long workId, Long requestId) {
    return response(commands.withdraw(member, workId, requestId));
  }

  public List<WorkCancellationResponse> getRequests(AuthenticatedMember member, Long workId) {
    return queries.findRequests(member, workId).stream().map(WorkCancellationResponse::from).toList();
  }

  // 만료 기록은 명령 트랜잭션에서 커밋하고, HTTP 충돌은 그 바깥에서 반환한다.
  private WorkCancellationResponse response(WorkCancellationRequest request) {
    if (request.getStatus() == WorkCancellationStatus.EXPIRED) throw new BusinessException(WorkErrorCode.CANCELLATION_EXPIRED);
    return WorkCancellationResponse.from(request);
  }
}
