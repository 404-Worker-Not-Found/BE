package com.workernotfound.job.domain.job.dto.response;

import com.workernotfound.job.domain.job.entity.IndustryCategory;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record JobCategoryListResponse(

        List<JobCategoryResponse> categories

) {
    // 활성 카테고리만 받는다. 하위 분류는 활성 대분류 아래에만 붙고, 비활성 대분류의 하위 분류는 노출하지 않는다.
    public static JobCategoryListResponse from(List<IndustryCategory> activeCategories) {
        Map<Long, List<IndustryCategory>> subCategoriesByParentId = activeCategories.stream()
                .filter(category -> category.getParentId() != null)
                .collect(Collectors.groupingBy(IndustryCategory::getParentId));
        List<JobCategoryResponse> categories = activeCategories.stream()
                .filter(category -> category.getParentId() == null)
                .map(category -> JobCategoryResponse.of(
                        category, subCategoriesByParentId.getOrDefault(category.getId(), List.of())))
                .toList();
        return new JobCategoryListResponse(categories);
    }
}
