package com.workernotfound.job.domain.job.entity;

import com.workernotfound.job.domain.job.entity.enums.PaymentChangeStatus;
import com.workernotfound.job.domain.job.entity.enums.PaymentChangeType;
import com.workernotfound.job.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 점주의 결제 조건 변경·재결제 요청과 적용 대기 중인 조건 스냅샷.
 *
 * <p>요청을 받으면 공고의 현재 조건과 주문 연결은 그대로 두고 이 행과 다음 순번의 주문 생성 명령을 함께 저장한다. 새 주문이 검증·연결되는
 * 트랜잭션에서만 이 조건을 공고에 적용(APPLIED)하고, payment-service가 교체를 확정적으로 거절하면 공고를 바꾸지 않고 종료(REJECTED)한다.
 * 요청 식별 필드와 조건 스냅샷은 생성 후 바꾸지 않는다.
 */
@Getter
@Entity
@Table(
        name = "job_payment_change_requests",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_job_payment_change_requests_idempotency_key",
                        columnNames = "idempotency_key"
                ),
                @UniqueConstraint(name = "uk_job_payment_change_requests_command", columnNames = "command_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobPaymentChangeRequest extends BaseEntity {

    @Column(nullable = false, updatable = false)
    private Long jobPostId;

    @Column(nullable = false, updatable = false)
    private Long ownerMemberId;

    @Column(nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private PaymentChangeType changeType;

    @Column(nullable = false, updatable = false)
    private Long commandId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentChangeStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDate workDate;

    @Column(nullable = false, updatable = false)
    private LocalTime startTime;

    @Column(nullable = false, updatable = false)
    private LocalTime endTime;

    @Column(nullable = false, updatable = false)
    private boolean endTimeNextDay;

    @Column(nullable = false, updatable = false)
    private Integer baseHourlyWage;

    @Column(updatable = false)
    private Integer extraWage;

    @Column(nullable = false, updatable = false)
    private Integer recruitCount;

    @Column(nullable = false, updatable = false)
    private LocalDateTime applicationDeadline;

    // 거절·적용 불가 사유. payment-service 오류 코드처럼 형식이 검증된 값만 저장한다.
    @Column(length = 50)
    private String resolutionCode;

    private LocalDateTime resolvedAt;

    @Builder
    private JobPaymentChangeRequest(
            Long jobPostId,
            Long ownerMemberId,
            String idempotencyKey,
            PaymentChangeType changeType,
            Long commandId,
            JobPaymentTerms terms
    ) {
        this.jobPostId = jobPostId;
        this.ownerMemberId = ownerMemberId;
        this.idempotencyKey = idempotencyKey;
        this.changeType = changeType;
        this.commandId = commandId;
        this.status = PaymentChangeStatus.PENDING;
        this.workDate = terms.workDate();
        this.startTime = terms.startTime();
        this.endTime = terms.endTime();
        this.endTimeNextDay = terms.endTimeNextDay();
        this.baseHourlyWage = terms.baseHourlyWage();
        this.extraWage = terms.extraWage();
        this.recruitCount = terms.recruitCount();
        this.applicationDeadline = terms.applicationDeadline();
    }

    public JobPaymentTerms terms() {
        return new JobPaymentTerms(workDate, startTime, endTime, endTimeNextDay, baseHourlyWage, extraWage,
                recruitCount, applicationDeadline);
    }

    // 같은 키의 재요청이 같은 요청인지 판단한다. 소유자 판단은 요청 본문이 아니라 인증된 회원 ID로 한다.
    public boolean isSameRequest(Long jobPostId, Long ownerMemberId, PaymentChangeType changeType, JobPaymentTerms terms) {
        return this.jobPostId.equals(jobPostId)
                && this.ownerMemberId.equals(ownerMemberId)
                && this.changeType == changeType
                && (terms == null || terms().equals(terms));
    }

    public boolean isPending() {
        return status == PaymentChangeStatus.PENDING;
    }

    public void apply(LocalDateTime now) {
        resolve(PaymentChangeStatus.APPLIED, null, now);
    }

    public void reject(String resolutionCode, LocalDateTime now) {
        resolve(PaymentChangeStatus.REJECTED, resolutionCode, now);
    }

    private void resolve(PaymentChangeStatus status, String resolutionCode, LocalDateTime now) {
        if (!isPending()) {
            throw new IllegalStateException("처리 중인 결제 변경 요청만 종료할 수 있습니다: " + this.status);
        }
        this.status = status;
        this.resolutionCode = resolutionCode;
        this.resolvedAt = now.truncatedTo(ChronoUnit.MICROS);
    }
}
