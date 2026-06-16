package com.workernotfound.member.domain.owner.service;

import com.workernotfound.member.domain.owner.entity.enums.BusinessVerificationStatus;
import org.springframework.stereotype.Service;

@Service
public class BusinessRegistrationFormatVerificationService implements BusinessVerificationService {

	private static final String BUSINESS_REGISTRATION_NUMBER_PATTERN = "^[0-9]{10}$|^[0-9]{3}-[0-9]{2}-[0-9]{5}$";

	@Override
	public BusinessVerificationResult verify(String businessRegistrationNumber) {
		boolean validFormat = businessRegistrationNumber.matches(BUSINESS_REGISTRATION_NUMBER_PATTERN);
		BusinessVerificationStatus status = validFormat
			? BusinessVerificationStatus.NOT_VERIFIED
			: BusinessVerificationStatus.FAILED;
		return new BusinessVerificationResult(validFormat, status);
	}
}
