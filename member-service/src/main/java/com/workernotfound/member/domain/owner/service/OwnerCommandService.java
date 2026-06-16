package com.workernotfound.member.domain.owner.service;

import com.workernotfound.member.domain.location.entity.Location;
import com.workernotfound.member.domain.member.entity.Member;
import com.workernotfound.member.domain.owner.entity.OwnerProfile;
import com.workernotfound.member.domain.owner.entity.enums.BusinessVerificationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OwnerCommandService {

	private final BusinessVerificationService businessVerificationService;

	public OwnerProfile createOwnerProfile(
		Member member,
		String businessRegistrationNumber,
		String businessType,
		BusinessVerificationStatus businessVerificationStatus,
		Location storeLocation
	) {
		validateBusinessRegistrationNumber(businessRegistrationNumber);
		return OwnerProfile.builder()
			.member(member)
			.businessRegistrationNumber(businessRegistrationNumber)
			.businessType(businessType)
			.businessVerificationStatus(businessVerificationStatus)
			.storeLocation(storeLocation)
			.build();
	}

	private void validateBusinessRegistrationNumber(String businessRegistrationNumber) {
		BusinessVerificationResult result = businessVerificationService.verify(businessRegistrationNumber);
		if (!result.validFormat()) {
			throw new IllegalArgumentException("사업자등록번호 형식이 올바르지 않습니다.");
		}
	}
}
