package com.workernotfound.member.external.client.nts;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.workernotfound.member.domain.owner.entity.enums.BusinessVerificationStatus;
import com.workernotfound.member.domain.owner.exception.OwnerErrorCode;
import com.workernotfound.member.domain.owner.service.BusinessVerificationResult;
import com.workernotfound.member.domain.owner.service.BusinessVerificationService;
import com.workernotfound.member.global.exception.BusinessException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
@RequiredArgsConstructor
public class NtsBusinessVerificationService implements BusinessVerificationService {

	private static final String STATUS_PATH = "/api/nts-businessman/v1/status";
	private static final String OPERATING_BUSINESS_STATUS_CODE = "01";
	private static final String BUSINESS_REGISTRATION_NUMBER_PATTERN = "^[0-9]{10}$|^[0-9]{3}-[0-9]{2}-[0-9]{5}$";

	private final RestClient ntsBusinessRestClient;
	private final NtsBusinessVerificationProperties properties;

	@Override
	public BusinessVerificationResult verify(String businessRegistrationNumber) {
		if (businessRegistrationNumber == null
			|| !businessRegistrationNumber.matches(BUSINESS_REGISTRATION_NUMBER_PATTERN)) {
			return new BusinessVerificationResult(false, BusinessVerificationStatus.FAILED);
		}
		if (!StringUtils.hasText(properties.serviceKey())) {
			throw unavailable();
		}

		String normalizedBusinessNumber = businessRegistrationNumber.replace("-", "");
		NtsStatusResponse response = requestStatus(normalizedBusinessNumber);
		if (response == null || !"OK".equals(response.statusCode()) || response.data() == null) {
			throw unavailable();
		}

		BusinessVerificationStatus status = response.data().stream()
			.filter(item -> normalizedBusinessNumber.equals(item.businessNumber()))
			.findFirst()
			.filter(item -> OPERATING_BUSINESS_STATUS_CODE.equals(item.businessStatusCode()))
			.map(item -> BusinessVerificationStatus.VERIFIED)
			.orElse(BusinessVerificationStatus.FAILED);
		return new BusinessVerificationResult(true, status);
	}

	private NtsStatusResponse requestStatus(String businessRegistrationNumber) {
		try {
			return ntsBusinessRestClient.post()
				.uri(uriBuilder -> uriBuilder
					.path(STATUS_PATH)
					.queryParam("serviceKey", properties.serviceKey())
					.build())
				.body(new NtsStatusRequest(List.of(businessRegistrationNumber)))
				.retrieve()
				.body(NtsStatusResponse.class);
		} catch (RestClientException exception) {
			log.warn("국세청 사업자등록 상태조회 API 호출에 실패했습니다.", exception);
			throw unavailable();
		}
	}

	private BusinessException unavailable() {
		return new BusinessException(OwnerErrorCode.BUSINESS_VERIFICATION_UNAVAILABLE);
	}

	private record NtsStatusRequest(
		@JsonProperty("b_no") List<String> businessNumbers
	) {
	}

	private record NtsStatusResponse(
		@JsonProperty("status_code") String statusCode,
		List<NtsBusinessStatus> data
	) {
	}

	private record NtsBusinessStatus(
		@JsonProperty("b_no") String businessNumber,
		@JsonProperty("b_stt_cd") String businessStatusCode
	) {
	}
}
