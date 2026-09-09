package com.workernotfound.member.domain.member.service;

import com.workernotfound.member.domain.member.dto.request.CreateOwnerMemberRequest;
import com.workernotfound.member.domain.member.dto.request.LocationRequest;
import com.workernotfound.member.domain.member.dto.response.CreateMemberResponse;
import com.workernotfound.member.domain.member.entity.enums.MemberRole;
import com.workernotfound.member.domain.owner.entity.enums.BusinessVerificationStatus;
import com.workernotfound.member.domain.owner.service.BusinessVerificationResult;
import com.workernotfound.member.domain.owner.service.BusinessVerificationService;
import com.workernotfound.member.support.IntegrationTestSupport;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class OwnerSignupTransactionBoundaryTests extends IntegrationTestSupport {

	@Autowired
	private MemberApplicationService memberApplicationService;

	@MockitoBean
	private BusinessVerificationService businessVerificationService;

	@Test
	void callsBusinessVerificationOutsideDatabaseTransaction() {
		when(businessVerificationService.verify("1234567890")).thenAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return new BusinessVerificationResult(true, BusinessVerificationStatus.VERIFIED);
		});

		CreateMemberResponse response = memberApplicationService.createOwnerMember(ownerRequest());

		memberApplicationService.deleteMemberForSignupCompensation(response.memberId());
	}

	private CreateOwnerMemberRequest ownerRequest() {
		return new CreateOwnerMemberRequest(
			"점주",
			"transaction-owner@example.com",
			"01055556666",
			MemberRole.OWNER,
			"1234567890",
			"트랜잭션 카페",
			"CAFE",
			new LocationRequest(
				"서울시 강남구 테헤란로 1",
				"101호",
				BigDecimal.valueOf(37.4979000),
				BigDecimal.valueOf(127.0276000)
			)
		);
	}
}
