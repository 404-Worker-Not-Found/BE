package com.workernotfound.job.domain.job.dto.request;

import com.workernotfound.job.domain.job.entity.JobPaymentTerms;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * 결제 대기 공고의 결제 조건 변경 요청. 변경 후의 전체 조건을 보낸다(부분 수정 없음).
 *
 * <p>검증 기준은 공고 등록과 같다. 점주는 요청 본문이 아니라 JWT의 회원 ID로 확인한다. 가게·제목·설명·위치·긴급도는 결제 조건이 아니라
 * 여기서 바꾸지 않는다.
 *
 * <p>현재 시각에 따라 결과가 달라지는 검증(지원 마감이 현재 이후, 근무일이 오늘 이후)은 여기에 두지 않는다. 같은 키의 재요청이 마감 뒤에
 * 와도 처음 처리 결과를 돌려받아야 하므로, 서비스가 기존 요청을 확인한 뒤 새 요청에만 적용한다.
 */
public record UpdatePaymentTermsRequest(

        @NotNull
        LocalDate workDate,

        @NotNull
        LocalTime startTime,

        @NotNull
        LocalTime endTime,

        boolean isEndTimeNextDay,

        @NotNull
        @Min(10320)
        Integer baseHourlyWage,

        @Positive
        Integer extraWage,

        @NotNull
        @Positive
        Integer recruitCount,

        @NotNull
        LocalDateTime applicationDeadline

) {

    // 저장 컬럼(DATETIME(6))과 같은 정밀도로 맞춰, 같은 키의 재요청을 저장된 조건과 그대로 비교한다.
    public JobPaymentTerms toTerms() {
        return new JobPaymentTerms(
                workDate,
                startTime,
                endTime,
                isEndTimeNextDay,
                baseHourlyWage,
                extraWage,
                recruitCount,
                applicationDeadline.truncatedTo(ChronoUnit.MICROS)
        );
    }
}
