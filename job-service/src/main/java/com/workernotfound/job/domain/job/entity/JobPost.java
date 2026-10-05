package com.workernotfound.job.domain.job.entity;

import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.UrgencyLevel;
import com.workernotfound.job.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Getter
@Entity
@Table(name = "job_posts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobPost extends BaseEntity {

    @Column(nullable = false)
    private Long businessId;

    @Column(nullable = false)
    private Long ownerId;

    @Column(nullable = false)
    private Long categoryId;

    @Column(nullable = false, length = 100)
    private String storeName;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(nullable = false)
    private LocalDate workDate;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @Column(nullable = false)
    private boolean endTimeNextDay;

    @Column(nullable = false)
    private Integer baseHourlyWage;

    private Integer extraWage;

    @Column(nullable = false)
    private Integer recruitCount;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UrgencyLevel urgencyLevel;

    @Column(nullable = false)
    private LocalDateTime applicationDeadline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Version
    @Column(nullable = false)
    private Long version = 1L;

    // 검증된 결제 주문과 그 주문을 만든 원래 결제 스냅샷. 주문 생성이 완료되기 전에는 null이다.
    // 결제용 버전은 현재 @Version이 아니라 주문 생성 명령 발급 당시의 버전이다.
    @Column(length = 64)
    private String paymentOrderId;

    private Long paymentJobVersion;

    private Long paymentAmount;

    @Column(length = 3)
    private String paymentCurrency;

    // 최신 주문의 예치 취소·검토 필요가 확인되어 신규 지원 승인과 신규 자리 예약을 막는다. 공고 상태와 별개다.
    @Column(nullable = false)
    private boolean fundingBlocked;

    @Builder
    private JobPost(
            Long businessId,
            Long ownerId,
            Long categoryId,
            String storeName,
            String address,
            String title,
            String description,
            LocalDate workDate,
            LocalTime startTime,
            LocalTime endTime,
            boolean endTimeNextDay,
            Integer baseHourlyWage,
            Integer extraWage,
            Integer recruitCount,
            BigDecimal latitude,
            BigDecimal longitude,
            UrgencyLevel urgencyLevel,
            LocalDateTime applicationDeadline
    ) {
        this.businessId = businessId;
        this.ownerId = ownerId;
        this.categoryId = categoryId;
        this.storeName = storeName;
        this.address = address;
        this.title = title;
        this.description = description;
        this.workDate = workDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.endTimeNextDay = endTimeNextDay;
        this.baseHourlyWage = baseHourlyWage;
        this.extraWage = extraWage;
        this.recruitCount = recruitCount;
        this.latitude = latitude;
        this.longitude = longitude;
        this.urgencyLevel = urgencyLevel;
        this.applicationDeadline = applicationDeadline;
        // 검증된 예치가 반영되기 전까지 비공개로 저장한다. 공개(OPEN) 전환은 예치 상태 수신(publishAfterFunding)이 맡는다.
        this.status = JobStatus.PAYMENT_PENDING;
    }

    /**
     * 주문 생성 명령의 검증된 결과를 연결한다. 상태는 바꾸지 않는다. 주문 생성만으로 공고를 공개하지 않으며 공개는 예치 확인 후의 일이다.
     * 호출자는 같은 트랜잭션에서 공고 행 잠금을 잡고, 이 명령이 공고의 최신 명령인지 확인해야 한다.
     */
    public void linkPaymentOrder(String orderId, Long jobVersion, Long amount, String currency) {
        this.paymentOrderId = orderId;
        this.paymentJobVersion = jobVersion;
        this.paymentAmount = amount;
        this.paymentCurrency = currency;
    }

    public boolean isLinkedToPaymentOrder(String orderId) {
        return paymentOrderId != null && paymentOrderId.equals(orderId);
    }

    // 연결된 주문의 원래 결제 스냅샷과 비교한다. 결제용 버전은 현재 @Version이 아니라 주문 생성 당시 버전이다.
    public boolean hasPaymentSnapshot(Long jobVersion, Long ownerMemberId, Long amount, String currency) {
        return paymentOrderId != null
                && paymentJobVersion.equals(jobVersion)
                && ownerId.equals(ownerMemberId)
                && paymentAmount.equals(amount)
                && paymentCurrency.equals(currency);
    }

    public void blockFunding() {
        this.fundingBlocked = true;
    }

    // 예치 차단만 해제한다. 공고 상태 전이는 publishAfterFunding이 따로 판단한다.
    public void unblockFunding() {
        this.fundingBlocked = false;
    }

    // 검증된 예치 확인으로 공개한다. 공개 기한 등 조건은 호출자가 공고 행 잠금 아래에서 확인한다.
    public void publishAfterFunding() {
        if (status != JobStatus.PAYMENT_PENDING) {
            throw new IllegalStateException("결제 대기 공고만 예치 확인으로 공개할 수 있습니다: " + status);
        }
        this.status = JobStatus.OPEN;
    }

    // 결제 대기 공고는 인증된 점주 본인에게만 보인다. 공개 이후 상태의 조회 정책은 바꾸지 않는다.
    public boolean isVisibleTo(Long viewerMemberId) {
        return status != JobStatus.PAYMENT_PENDING || ownerId.equals(viewerMemberId);
    }

    // 모집 완료로 마감할 수 있는 상태다. 이미 CLOSED인 공고는 다시 마감하지 않는다.
    public boolean isRecruiting() {
        return status == JobStatus.OPEN || status == JobStatus.MATCHING;
    }

    public void closeForRecruitmentCompletion() {
        if (!isRecruiting()) {
            throw new IllegalStateException("모집 중인 공고만 모집 완료로 마감할 수 있습니다: " + status);
        }
        this.status = JobStatus.CLOSED;
    }
}
