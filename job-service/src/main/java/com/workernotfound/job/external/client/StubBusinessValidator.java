package com.workernotfound.job.external.client;

import com.workernotfound.job.domain.job.port.BusinessValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// TODO: member-service 연동 시 실제 HTTP 클라이언트로 교체
@Slf4j
@Component
public class StubBusinessValidator implements BusinessValidator {

    @Override
    public void validateOwnership(Long businessId, Long ownerId) {
        log.warn("[StubBusinessValidator] businessId={}, ownerId={} 소유권 검증 미구현 — member-service 연동 전", businessId, ownerId);
    }
}
