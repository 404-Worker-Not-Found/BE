package com.workernotfound.payment.domain.funding;

import com.workernotfound.payment.global.exception.*;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FundingIncidentApplicationService {
  private final FundingIncidentRepository incidents;
  @Transactional(readOnly=true)
  public Map<String,Object> getIncident(long id) {
    return incidents.findIncident(id).orElseThrow(()->new BusinessException(GlobalErrorCode.NOT_FOUND));
  }
}
