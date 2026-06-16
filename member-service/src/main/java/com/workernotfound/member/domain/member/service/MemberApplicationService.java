package com.workernotfound.member.domain.member.service;

import com.workernotfound.member.domain.location.entity.Location;
import com.workernotfound.member.domain.location.service.LocationCommandService;
import com.workernotfound.member.domain.member.dto.request.CreateOwnerMemberRequest;
import com.workernotfound.member.domain.member.dto.request.CreateWorkerMemberRequest;
import com.workernotfound.member.domain.member.dto.request.LocationRequest;
import com.workernotfound.member.domain.member.dto.response.CreateMemberResponse;
import com.workernotfound.member.domain.member.dto.response.LocationResponse;
import com.workernotfound.member.domain.member.dto.response.MemberInternalResponse;
import com.workernotfound.member.domain.member.dto.response.MyMemberResponse;
import com.workernotfound.member.domain.member.dto.response.OwnerProfileResponse;
import com.workernotfound.member.domain.member.dto.response.WorkerAvailableTimeResponse;
import com.workernotfound.member.domain.member.dto.response.WorkerProfileResponse;
import com.workernotfound.member.domain.member.entity.Member;
import com.workernotfound.member.domain.member.entity.enums.MemberRole;
import com.workernotfound.member.domain.owner.entity.OwnerProfile;
import com.workernotfound.member.domain.owner.service.OwnerCommandService;
import com.workernotfound.member.domain.worker.entity.WorkerAvailableTime;
import com.workernotfound.member.domain.worker.entity.WorkerPreferredBusinessType;
import com.workernotfound.member.domain.worker.entity.WorkerProfile;
import com.workernotfound.member.domain.worker.service.WorkerCommandService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberApplicationService {

	private final MemberCommandService memberCommandService;
	private final MemberFindService memberFindService;
	private final LocationCommandService locationCommandService;
	private final OwnerCommandService ownerCommandService;
	private final WorkerCommandService workerCommandService;

	@Transactional
	public CreateMemberResponse createOwnerMember(CreateOwnerMemberRequest request) {
		validateRole(request.role(), MemberRole.OWNER);
		Member member = memberCommandService.createMember(
			request.name(),
			request.email(),
			request.phoneNumber(),
			request.role()
		);
		Location storeLocation = createLocation(request.storeLocation());
		OwnerProfile ownerProfile = ownerCommandService.createOwnerProfile(
			member,
			request.businessRegistrationNumber(),
			request.businessType(),
			request.businessVerificationStatus(),
			storeLocation
		);
		member.registerOwnerProfile(ownerProfile);
		return toCreateMemberResponse(memberCommandService.saveMember(member));
	}

	@Transactional
	public CreateMemberResponse createWorkerMember(CreateWorkerMemberRequest request) {
		validateRole(request.role(), MemberRole.WORKER);
		Member member = memberCommandService.createMember(
			request.name(),
			request.email(),
			request.phoneNumber(),
			request.role()
		);
		WorkerProfile workerProfile = createWorkerProfile(member, request);
		member.registerWorkerProfile(workerProfile);
		return toCreateMemberResponse(memberCommandService.saveMember(member));
	}

	@Transactional(readOnly = true)
	public MemberInternalResponse getMemberInternal(Long memberId) {
		Member member = memberFindService.findMember(memberId);
		return toMemberInternalResponse(member);
	}

	@Transactional
	public void deleteMemberForSignupCompensation(Long memberId) {
		Member member = memberFindService.findMember(memberId);
		memberCommandService.deleteMember(member);
	}

	@Transactional(readOnly = true)
	public MyMemberResponse getMyMember(Long memberId) {
		Member member = memberFindService.findActiveMember(memberId);
		return toMyMemberResponse(member);
	}

	private WorkerProfile createWorkerProfile(Member member, CreateWorkerMemberRequest request) {
		Location baseLocation = createLocation(request.baseLocation());
		WorkerProfile workerProfile = workerCommandService.createWorkerProfile(
			member,
			request.desiredHourlyWage(),
			request.activityRadiusKm(),
			request.immediatelyAvailable(),
			baseLocation
		);
		workerCommandService.addPreferredBusinessTypes(workerProfile, request.preferredBusinessTypes());
		request.availableTimes().forEach(availableTime -> workerCommandService.addAvailableTime(
			workerProfile,
			availableTime.dayOfWeek(),
			availableTime.startTime(),
			availableTime.endTime()
		));
		return workerProfile;
	}

	private Location createLocation(LocationRequest request) {
		return locationCommandService.createLocation(
			request.address(),
			request.detailAddress(),
			request.latitude(),
			request.longitude()
		);
	}

	private void validateRole(MemberRole actual, MemberRole expected) {
		if (actual != expected) {
			throw new IllegalArgumentException("회원 역할이 요청한 가입 유형과 일치하지 않습니다.");
		}
	}

	private CreateMemberResponse toCreateMemberResponse(Member member) {
		return new CreateMemberResponse(
			member.getId(),
			member.getEmail(),
			member.getPhoneNumber(),
			member.getRole(),
			member.getStatus()
		);
	}

	private MemberInternalResponse toMemberInternalResponse(Member member) {
		return new MemberInternalResponse(
			member.getId(),
			member.getName(),
			member.getEmail(),
			member.getPhoneNumber(),
			member.getRole(),
			member.getStatus()
		);
	}

	private MyMemberResponse toMyMemberResponse(Member member) {
		return new MyMemberResponse(
			member.getId(),
			member.getName(),
			member.getEmail(),
			member.getPhoneNumber(),
			member.getRole(),
			member.getStatus(),
			member.getJoinedAt(),
			toOwnerProfileResponse(member.getOwnerProfile()),
			toWorkerProfileResponse(member.getWorkerProfile())
		);
	}

	private OwnerProfileResponse toOwnerProfileResponse(OwnerProfile ownerProfile) {
		if (ownerProfile == null) {
			return null;
		}
		return new OwnerProfileResponse(
			ownerProfile.getId(),
			ownerProfile.getBusinessRegistrationNumber(),
			ownerProfile.getBusinessType(),
			ownerProfile.getBusinessVerificationStatus(),
			toLocationResponse(ownerProfile.getStoreLocation())
		);
	}

	private WorkerProfileResponse toWorkerProfileResponse(WorkerProfile workerProfile) {
		if (workerProfile == null) {
			return null;
		}
		return new WorkerProfileResponse(
			workerProfile.getId(),
			workerProfile.getDesiredHourlyWage(),
			workerProfile.getActivityRadiusKm(),
			workerProfile.isImmediatelyAvailable(),
			toLocationResponse(workerProfile.getBaseLocation()),
			toPreferredBusinessTypes(workerProfile.getPreferredBusinessTypes()),
			toAvailableTimes(workerProfile.getAvailableTimes())
		);
	}

	private LocationResponse toLocationResponse(Location location) {
		return new LocationResponse(
			location.getId(),
			location.getAddress(),
			location.getDetailAddress(),
			location.getLatitude(),
			location.getLongitude()
		);
	}

	private List<String> toPreferredBusinessTypes(List<WorkerPreferredBusinessType> businessTypes) {
		return businessTypes.stream()
			.map(WorkerPreferredBusinessType::getBusinessType)
			.toList();
	}

	private List<WorkerAvailableTimeResponse> toAvailableTimes(List<WorkerAvailableTime> availableTimes) {
		return availableTimes.stream()
			.map(this::toAvailableTimeResponse)
			.toList();
	}

	private WorkerAvailableTimeResponse toAvailableTimeResponse(WorkerAvailableTime availableTime) {
		return new WorkerAvailableTimeResponse(
			availableTime.getId(),
			availableTime.getDayOfWeek(),
			availableTime.getStartTime(),
			availableTime.getEndTime()
		);
	}
}
