package com.workernotfound.job.domain.job.dto.response;

import com.workernotfound.job.domain.job.entity.IndustryCategory;
import java.util.List;

public record JobCategoryResponse(

        Long id,

        String name,

        List<JobSubCategoryResponse> subCategories

) {
    public static JobCategoryResponse of(IndustryCategory category, List<IndustryCategory> subCategories) {
        return new JobCategoryResponse(
                category.getId(),
                category.getName(),
                subCategories.stream().map(JobSubCategoryResponse::from).toList()
        );
    }
}
