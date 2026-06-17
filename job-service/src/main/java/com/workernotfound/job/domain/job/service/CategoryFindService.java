package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.IndustryCategory;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.domain.job.repository.IndustryCategoryRepository;
import com.workernotfound.job.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryFindService {

    private final IndustryCategoryRepository industryCategoryRepository;

    public IndustryCategory findCategory(Long categoryId) {
        return industryCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new BusinessException(JobErrorCode.CATEGORY_NOT_FOUND));
    }
}
