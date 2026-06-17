package com.workernotfound.job.domain.job.dto.response;

import java.util.List;

public record JobSearchResponse(

        int page,

        int size,

        int totalCount,

        int totalPages,

        List<JobCardResponse> jobs

) {
    public static JobSearchResponse of(List<JobCardResponse> pagedJobs, int page, int size, int totalCount) {
        int totalPages = size > 0 ? (int) Math.ceil((double) totalCount / size) : 0;
        return new JobSearchResponse(page, size, totalCount, totalPages, pagedJobs);
    }
}
