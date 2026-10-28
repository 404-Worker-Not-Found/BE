package com.workernotfound.payment.domain.settlement.service;

import com.workernotfound.payment.domain.settlement.dto.SettlementResponse;
import com.workernotfound.payment.domain.settlement.repository.SettlementQueryRepository;
import com.workernotfound.payment.global.exception.BusinessException;
import com.workernotfound.payment.global.exception.GlobalErrorCode;
import com.workernotfound.payment.global.security.AuthenticatedMember;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettlementApplicationService {
  private final SettlementQueryRepository queries;

  public List<SettlementResponse> getSettlements(AuthenticatedMember member, Long beforeId, int size) {
    if (size < 1 || size > 100 || (beforeId != null && beforeId < 1))
      throw new BusinessException(GlobalErrorCode.INVALID_REQUEST);
    return queries.findParticipantSettlements(member.memberId(), member.role(), beforeId, size);
  }

  public SettlementResponse getSettlement(AuthenticatedMember member, long id) {
    return queries.findParticipantSettlement(member.memberId(), member.role(), id)
        .orElseThrow(() -> new BusinessException(SettlementErrorCode.NOT_FOUND));
  }
}
