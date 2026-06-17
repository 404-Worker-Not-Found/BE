package com.workernotfound.job.domain.job.entity;

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
        this.baseHourlyWage = baseHourlyWage;
        this.extraWage = extraWage;
        this.recruitCount = recruitCount;
        this.latitude = latitude;
        this.longitude = longitude;
        this.urgencyLevel = urgencyLevel;
        this.applicationDeadline = applicationDeadline;
        this.status = JobStatus.OPEN;
    }
}
