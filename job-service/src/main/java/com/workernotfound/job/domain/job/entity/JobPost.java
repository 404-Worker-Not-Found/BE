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
        // 검증된 예치가 반영되기 전까지 비공개로 저장한다. 공개(OPEN) 전환은 예치 상태 수신 후속 작업이 맡는다.
        this.status = JobStatus.PAYMENT_PENDING;
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
