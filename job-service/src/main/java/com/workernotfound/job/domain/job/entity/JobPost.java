package com.workernotfound.job.domain.job.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "job_posts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * user-service.businesses.id
     */
    @Column(nullable = false)
    private Long businessId;

    /**
     * user-service.users.id
     */
    @Column(nullable = false)
    private Long ownerId;

    @Column(nullable = false)
    private Long categoryId;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 200)
    private String description;

    /**
     * 근무 날짜
     */
    @Column(nullable = false)
    private LocalDate workDate;

    /**
     * 시작 시간
     */
    @Column(nullable = false)
    private LocalTime startTime;

    /**
     * 종료 시간
     */
    @Column(nullable = false)
    private LocalTime endTime;

    @Column(nullable = false)
    private Integer baseHourlyWage;

    @Column(nullable = false)
    private Integer recruitCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    protected JobPost(
            Long businessId,
            Long ownerId,
            Long categoryId,
            String title,
            String description,
            LocalDate workDate,
            LocalTime startTime,
            LocalTime endTime,
            Integer baseHourlyWage,
            Integer recruitCount
    ) {
        this.businessId = businessId;
        this.ownerId = ownerId;
        this.categoryId = categoryId;
        this.title = title;
        this.description = description;
        this.workDate = workDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.baseHourlyWage = baseHourlyWage;
        this.recruitCount = recruitCount;
        this.status = JobStatus.OPEN;
    }

    public static JobPost create(
            Long businessId,
            Long ownerId,
            Long categoryId,
            String title,
            String description,
            LocalDate workDate,
            LocalTime startTime,
            LocalTime endTime,
            Integer baseHourlyWage,
            Integer recruitCount
    ) {
        return new JobPost(businessId, ownerId, categoryId, title, description, workDate, startTime, endTime, baseHourlyWage, recruitCount);
    }
}