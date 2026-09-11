package com.workernotfound.matching.domain.application.dto.response;

import com.workernotfound.matching.domain.application.entity.Application;
import java.util.List;
import org.springframework.data.domain.Page;

public record ApplicationListResponse(
	int page,
	int size,
	long totalCount,
	int totalPages,
	List<ApplicationResponse> applications
) {

	public static ApplicationListResponse from(Page<Application> page) {
		return new ApplicationListResponse(
			page.getNumber(),
			page.getSize(),
			page.getTotalElements(),
			page.getTotalPages(),
			page.getContent().stream().map(ApplicationResponse::from).toList()
		);
	}
}
