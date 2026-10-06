package com.workernotfound.job.domain.job.dto.response;

import java.util.List;

public record OwnerJobListResponse(

        int page,

        int size,

        long totalCount,

        int totalPages,

        List<OwnerJobCardResponse> jobs

) {
}
