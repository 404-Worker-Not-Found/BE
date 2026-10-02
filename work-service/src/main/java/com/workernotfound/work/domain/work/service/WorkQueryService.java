package com.workernotfound.work.domain.work.service;

import com.workernotfound.work.domain.work.dto.response.*;
import com.workernotfound.work.domain.work.exception.WorkErrorCode;
import com.workernotfound.work.domain.work.repository.WorkRepository;
import com.workernotfound.work.global.exception.BusinessException;
import com.workernotfound.work.global.security.AuthenticatedMember;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkQueryService {
  private final WorkRepository works;

  public WorkPageResponse getWorks(AuthenticatedMember member, int page, int size) {
    var result =
        works.findConfirmedByMember(
            member.memberId(),
            member.role(),
            PageRequest.of(page, size, Sort.by("workDate", "startTime", "id").descending()));
    return new WorkPageResponse(
        result.getContent().stream().map(WorkResponse::from).toList(),
        page,
        size,
        result.getTotalElements(),
        result.getTotalPages());
  }

  public WorkResponse getWork(AuthenticatedMember member, Long workId) {
    return works
        .findConfirmedByIdAndMember(workId, member.memberId(), member.role())
        .map(WorkResponse::from)
        .orElseThrow(() -> new BusinessException(WorkErrorCode.WORK_NOT_FOUND));
  }
}
