package com.workernotfound.job.domain.job.repository;

import com.workernotfound.job.domain.job.entity.IndustryCategory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IndustryCategoryRepository
        extends JpaRepository<IndustryCategory, Long> {
}