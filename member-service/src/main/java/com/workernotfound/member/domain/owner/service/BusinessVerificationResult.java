package com.workernotfound.member.domain.owner.service;

import com.workernotfound.member.domain.owner.entity.enums.BusinessVerificationStatus;

public record BusinessVerificationResult(
	boolean validFormat,
	BusinessVerificationStatus status
) {
}
