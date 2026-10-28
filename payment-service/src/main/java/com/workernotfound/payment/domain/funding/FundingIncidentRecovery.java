package com.workernotfound.payment.domain.funding;

import com.workernotfound.payment.external.funding.FundingIncidentClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name="payment.funding-incidents.enabled",havingValue="true",matchIfMissing=true)
public class FundingIncidentRecovery {
  private final FundingIncidentRepository incidents;
  private final FundingIncidentClient client;

  @Scheduled(fixedDelayString="${payment.funding-incidents.interval:30s}",initialDelayString="${payment.funding-incidents.initial-delay:30s}")
  public void recover() {
    for(long id:incidents.findUnattached()) {
      try { incidents.attach(id); }
      catch(RuntimeException e) { log.warn("예치 이상 대상 연결 실패: incidentId={}, type={}",id,e.getClass().getSimpleName()); }
    }
    for(int i=0;i<20;i++) {
      var delivery=incidents.claim();if(delivery==null) break;
      try { client.deliver(delivery);incidents.finish(delivery,true); }
      catch(RuntimeException e) {
        incidents.finish(delivery,false);
        log.warn("예치 이상 알림 재시도: deliveryId={}, type={}",delivery.id(),e.getClass().getSimpleName());
      }
    }
  }
}
