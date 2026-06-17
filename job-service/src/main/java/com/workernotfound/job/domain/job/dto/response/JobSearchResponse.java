package com.workernotfound.job.domain.job.dto.response;

import java.util.List;

public record JobSearchResponse(

        int totalCount,

        List<JobCardResponse> jobs

) {
    public static JobSearchResponse of(List<JobCardResponse> jobs) {
        return new JobSearchResponse(jobs.size(), jobs);
    }
}
