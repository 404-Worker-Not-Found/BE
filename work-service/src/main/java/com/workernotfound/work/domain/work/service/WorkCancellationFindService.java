package com.workernotfound.work.domain.work.service;

import com.workernotfound.work.domain.work.entity.WorkCancellationRequest;
import com.workernotfound.work.domain.work.exception.WorkErrorCode;
import com.workernotfound.work.domain.work.repository.*;
import com.workernotfound.work.global.exception.BusinessException;
import com.workernotfound.work.global.security.AuthenticatedMember;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkCancellationFindService {
  private final WorkRepository works;
  private final WorkCancellationRequestRepository requests;

  @Transactional(readOnly = true)
  public List<WorkCancellationRequest> findRequests(AuthenticatedMember member, Long workId) {
    works.findConfirmedByIdAndMember(workId, member.memberId(), member.role())
        .orElseThrow(() -> new BusinessException(WorkErrorCode.WORK_NOT_FOUND));
    return requests.findTop20ByWorkIdOrderByIdDesc(workId);
  }
}
