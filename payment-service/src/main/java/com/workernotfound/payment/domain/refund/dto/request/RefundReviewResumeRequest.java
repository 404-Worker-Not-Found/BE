package com.workernotfound.payment.domain.refund.dto.request;

import jakarta.validation.constraints.*;

// 운영자 ID와 검토 사유는 콜론 없는 형식으로 제한해 재개 명령 지문의 값 경계를 지킨다.
public record RefundReviewResumeRequest(
    @NotNull @Pattern(regexp = "[A-Za-z0-9._@-]{1,64}") String operatorId,
    @NotNull @Pattern(regexp = "[A-Z_]{1,64}") String reviewReason,
    @NotBlank @Size(max = 200) String note) {}
