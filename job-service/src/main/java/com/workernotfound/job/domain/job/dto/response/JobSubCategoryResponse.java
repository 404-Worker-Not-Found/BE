package com.workernotfound.job.domain.job.dto.response;

import com.workernotfound.job.domain.job.entity.IndustryCategory;

public record JobSubCategoryResponse(

        Long id,

        String name

) {
    public static JobSubCategoryResponse from(IndustryCategory category) {
        return new JobSubCategoryResponse(category.getId(), category.getName());
    }
}
