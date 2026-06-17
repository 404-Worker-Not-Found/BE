package com.workernotfound.job.domain.job.entity;

import com.workernotfound.job.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "industry_categories")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IndustryCategory extends BaseEntity {

    @Column(nullable = false)
    private String name;

    private Long parentId;

    @Column(nullable = false)
    private Boolean isActive;
}
