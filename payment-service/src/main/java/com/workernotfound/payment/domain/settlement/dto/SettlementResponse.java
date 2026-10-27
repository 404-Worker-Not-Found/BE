package com.workernotfound.payment.domain.settlement.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SettlementResponse(Long id, Long workId, Long matchingId, Long jobPostId,
    BigDecimal amount, String currency, String status, String holdReason,
    LocalDateTime workCompletedAt, LocalDateTime payableAt, LocalDateTime paidAt) {}
