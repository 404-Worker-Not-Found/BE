package com.workernotfound.member.domain.member.service;

import com.workernotfound.member.domain.location.repository.LocationRepository;
import com.workernotfound.member.domain.member.dto.request.CreateOwnerMemberRequest;
import com.workernotfound.member.domain.member.dto.request.CreateWorkerMemberRequest;
import com.workernotfound.member.domain.member.dto.request.LocationRequest;
import com.workernotfound.member.domain.member.dto.request.WorkerAvailableTimeRequest;
import com.workernotfound.member.domain.member.dto.response.CreateMemberResponse;
import com.workernotfound.member.domain.member.entity.enums.MemberRole;
import com.workernotfound.member.domain.member.repository.MemberRepository;
import com.workernotfound.member.domain.owner.entity.OwnerProfile;
import com.workernotfound.member.domain.owner.entity.enums.BusinessVerificationStatus;
import com.workernotfound.member.domain.owner.repository.OwnerProfileRepository;
import com.workernotfound.member.domain.owner.service.BusinessVerificationResult;
import com.workernotfound.member.domain.owner.service.BusinessVerificationService;
import com.workernotfound.member.domain.worker.entity.WorkerProfile;
import com.workernotfound.member.domain.worker.repository.WorkerAvailableTimeRepository;
import com.workernotfound.member.domain.worker.repository.WorkerPreferredBusinessTypeRepository;
import com.workernotfound.member.domain.worker.repository.WorkerProfileRepository;
import com.workernotfound.member.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@Transactional
class MemberSignupCompensationTests extends IntegrationTestSupport {

	@Autowired
	private MemberApplicationService memberApplicationService;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private OwnerProfileRepository ownerProfileRepository;

	@Autowired
	private WorkerProfileRepository workerProfileRepository;

	@Autowired
	private WorkerPreferredBusinessTypeRepository workerPreferredBusinessTypeRepository;

	@Autowired
	private WorkerAvailableTimeRepository workerAvailableTimeRepository;

	@Autowired
	private LocationRepository locationRepository;

	@Autowired
	private EntityManager entityManager;

	@MockitoBean
	private BusinessVerificationService businessVerificationService;

	@BeforeEach
	void setUpBusinessVerification() {
		when(businessVerificationService.verify(anyString()))
			.thenReturn(new BusinessVerificationResult(true, BusinessVerificationStatus.VERIFIED));
	}

	@Test
	void deleteMemberForSignupCompensationDeletesOwnerProfileAndStoreLocation() {
		CreateMemberResponse response = memberApplicationService.createOwnerMember(ownerRequest());
		OwnerProfile ownerProfile = ownerProfileRepository.findByMember_Id(response.memberId()).orElseThrow();
		Long ownerProfileId = ownerProfile.getId();
		Long storeLocationId = ownerProfile.getStoreLocation().getId();
		assertThat(ownerProfile.getStoreName()).isEqualTo("일하는 카페");
		assertThat(ownerProfile.getBusinessVerificationStatus()).isEqualTo(BusinessVerificationStatus.VERIFIED);

		memberApplicationService.deleteMemberForSignupCompensation(response.memberId());
		flushAndClear();

		assertThat(memberRepository.existsById(response.memberId())).isFalse();
		assertThat(ownerProfileRepository.existsById(ownerProfileId)).isFalse();
		assertThat(locationRepository.existsById(storeLocationId)).isFalse();
	}

	@Test
	void deleteMemberForSignupCompensationDeletesWorkerProfileLocationAndChildren() {
		CreateMemberResponse response = memberApplicationService.createWorkerMember(workerRequest());
		WorkerProfile workerProfile = workerProfileRepository.findByMember_Id(response.memberId()).orElseThrow();
		Long workerProfileId = workerProfile.getId();
		Long baseLocationId = workerProfile.getBaseLocation().getId();

		memberApplicationService.deleteMemberForSignupCompensation(response.memberId());
		flushAndClear();

		assertThat(memberRepository.existsById(response.memberId())).isFalse();
		assertThat(workerProfileRepository.existsById(workerProfileId)).isFalse();
		assertThat(locationRepository.existsById(baseLocationId)).isFalse();
		assertThat(workerPreferredBusinessTypeRepository.findAllByWorkerProfile_Id(workerProfileId)).isEmpty();
		assertThat(workerAvailableTimeRepository.findAllByWorkerProfile_Id(workerProfileId)).isEmpty();
	}

	private CreateOwnerMemberRequest ownerRequest() {
		return new CreateOwnerMemberRequest(
			"오너",
			"owner-compensation@example.com",
			"01011112222",
			MemberRole.OWNER,
			"1234567890",
			"일하는 카페",
			"CAFE",
			locationRequest()
		);
	}

	private CreateWorkerMemberRequest workerRequest() {
		return new CreateWorkerMemberRequest(
			"워커",
			"worker-compensation@example.com",
			"01033334444",
			MemberRole.WORKER,
			12000,
			5,
			true,
			locationRequest(),
			List.of("CAFE", "RESTAURANT"),
			List.of(
				new WorkerAvailableTimeRequest(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(18, 0)),
				new WorkerAvailableTimeRequest(DayOfWeek.TUESDAY, LocalTime.of(10, 0), LocalTime.of(17, 0))
			)
		);
	}

	private LocationRequest locationRequest() {
		return new LocationRequest(
			"서울시 강남구 테헤란로 1",
			"101호",
			BigDecimal.valueOf(37.4979000),
			BigDecimal.valueOf(127.0276000)
		);
	}

	private void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
	}
}
