package com.workernotfound.member.domain.owner.service;

import com.workernotfound.member.domain.location.entity.Location;
import com.workernotfound.member.domain.member.entity.Member;
import com.workernotfound.member.domain.owner.entity.OwnerProfile;
import com.workernotfound.member.domain.owner.entity.enums.BusinessVerificationStatus;
import com.workernotfound.member.domain.owner.exception.OwnerErrorCode;
import com.workernotfound.member.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OwnerCommandService {

	private final BusinessVerificationService businessVerificationService;

	public OwnerProfile createOwnerProfile(
		Member member,
		String businessRegistrationNumber,
		String storeName,
		String businessType,
		Location storeLocation
	) {
		BusinessVerificationResult verificationResult = verifyBusinessRegistrationNumber(businessRegistrationNumber);
		return OwnerProfile.builder()
			.member(member)
			.businessRegistrationNumber(businessRegistrationNumber)
			.storeName(storeName)
			.businessType(businessType)
			.businessVerificationStatus(verificationResult.status())
			.storeLocation(storeLocation)
			.build();
	}

	private BusinessVerificationResult verifyBusinessRegistrationNumber(String businessRegistrationNumber) {
		BusinessVerificationResult result = businessVerificationService.verify(businessRegistrationNumber);
		if (!result.validFormat()) {
			throw new BusinessException(OwnerErrorCode.INVALID_BUSINESS_REGISTRATION_NUMBER);
		}
		if (result.status() != BusinessVerificationStatus.VERIFIED) {
			throw new BusinessException(OwnerErrorCode.BUSINESS_NOT_OPERATING);
		}
		return result;
	}
}
