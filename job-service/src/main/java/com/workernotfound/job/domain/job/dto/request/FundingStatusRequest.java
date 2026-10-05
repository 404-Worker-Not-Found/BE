package com.workernotfound.job.domain.job.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.workernotfound.job.domain.job.entity.FundingStatusNotification;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * payment-service가 보내는 예치 상태 알림 본문. 필드는 payment-service의 주문 스냅샷(`payment_orders`)과 알림 revision이다.
 *
 * <p>금액은 payment-service의 DECIMAL(19,2) 값이라 {@code 100000.00}처럼 소수 표기로 올 수 있다. 정수 KRW인지만 확인하고 scale은
 * 비교하지 않는다. {@code funded}는 래퍼 타입이라 누락·null을 false로 해석하지 않고 400으로 거절한다. 버전·회원 ID·revision은
 * 정수 JSON 숫자, {@code funded}는 JSON 불리언만 받는다. 소수나 문자열을 형 변환해 다른 값으로 비교하지 않는다.
 */
public record FundingStatusRequest(
        @NotNull @Pattern(regexp = "[A-Za-z0-9-]{1,64}") String orderId,
        @NotNull @Positive @JsonDeserialize(using = StrictJsonDeserializers.IntegerLong.class) Long jobVersion,
        @NotNull @Positive @JsonDeserialize(using = StrictJsonDeserializers.IntegerLong.class) Long ownerMemberId,
        @NotNull @DecimalMin("100") @DecimalMax("99999999999999999") BigDecimal amount,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull @Positive @JsonDeserialize(using = StrictJsonDeserializers.IntegerLong.class) Long fundingRevision,
        @NotNull @JsonDeserialize(using = StrictJsonDeserializers.BooleanValue.class) Boolean funded
) {

    @JsonIgnore
    @AssertTrue(message = "amount는 정수 KRW여야 합니다.")
    public boolean isWholeAmount() {
        return amount == null || amount.stripTrailingZeros().scale() <= 0;
    }

    public FundingStatusNotification toNotification() {
        return new FundingStatusNotification(
                orderId,
                jobVersion,
                ownerMemberId,
                amount.longValueExact(),
                currency,
                fundingRevision,
                funded
        );
    }
}
