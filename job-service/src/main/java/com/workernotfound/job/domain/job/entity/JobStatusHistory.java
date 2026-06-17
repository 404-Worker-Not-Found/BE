package com.workernotfound.job.domain.job.entity;

import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "job_status_histories")
@Getter
@NoArgsConstructor
public class JobStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long jobPostId;

    @Enumerated(EnumType.STRING)
    private JobStatus fromStatus;

    @Enumerated(EnumType.STRING)
    private JobStatus toStatus;

    private String reason;

    private LocalDateTime createdAt;
}