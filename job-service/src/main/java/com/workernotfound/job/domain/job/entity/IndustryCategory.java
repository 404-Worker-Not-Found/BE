package com.workernotfound.job.domain.job.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "industry_categories")
@Getter
@NoArgsConstructor
public class IndustryCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private Long parentId;

    private Boolean isActive;
}