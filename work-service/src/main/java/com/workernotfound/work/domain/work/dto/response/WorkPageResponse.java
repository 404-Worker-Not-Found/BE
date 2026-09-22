package com.workernotfound.work.domain.work.dto.response;

import java.util.List;

public record WorkPageResponse(
    List<WorkResponse> content, int page, int size, long totalElements, int totalPages) {}
