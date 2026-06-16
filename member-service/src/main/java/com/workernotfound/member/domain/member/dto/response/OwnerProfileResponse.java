package com.workernotfound.member.domain.member.dto.response;

import com.workernotfound.member.domain.owner.entity.enums.BusinessVerificationStatus;

public record OwnerProfileResponse(
	Long ownerProfileId,
	String businessRegistrationNumber,
	String businessType,
	BusinessVerificationStatus businessVerificationStatus,
	LocationResponse storeLocation
) {
}
