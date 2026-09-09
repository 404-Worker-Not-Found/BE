package com.workernotfound.member.domain.owner.service;

import com.workernotfound.member.domain.location.entity.Location;
import com.workernotfound.member.domain.member.entity.Member;
import com.workernotfound.member.domain.owner.entity.enums.BusinessVerificationStatus;
import com.workernotfound.member.domain.owner.exception.OwnerErrorCode;
import com.workernotfound.member.global.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OwnerCommandServiceTests {

	@Test
	void rejectsBusinessThatIsNotOperating() {
		BusinessVerificationService verificationService = mock(BusinessVerificationService.class);
		when(verificationService.verify("1234567890"))
			.thenReturn(new BusinessVerificationResult(true, BusinessVerificationStatus.FAILED));
		OwnerCommandService ownerCommandService = new OwnerCommandService(verificationService);

		assertThatThrownBy(() -> ownerCommandService.createOwnerProfile(
			mock(Member.class),
			"1234567890",
			"일하는 카페",
			"CAFE",
			mock(Location.class)
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.getErrorCode()).isEqualTo(OwnerErrorCode.BUSINESS_NOT_OPERATING));
	}
}
