package com.workernotfound.member.domain.member.service;

import com.workernotfound.member.domain.location.entity.Location;
import com.workernotfound.member.domain.member.dto.request.*;
import com.workernotfound.member.domain.member.dto.response.MyMemberResponse;
import com.workernotfound.member.domain.member.entity.Member;
import com.workernotfound.member.domain.member.entity.enums.MemberStatus;
import com.workernotfound.member.domain.member.exception.MemberErrorCode;
import com.workernotfound.member.domain.member.repository.MemberRepository;
import com.workernotfound.member.domain.worker.entity.WorkerAvailableTime;
import com.workernotfound.member.domain.worker.entity.WorkerProfile;
import com.workernotfound.member.domain.worker.exception.WorkerErrorCode;
import com.workernotfound.member.domain.worker.service.WorkerCommandService;
import com.workernotfound.member.global.account.AccountGateService;
import com.workernotfound.member.global.exception.BusinessException;
import java.util.HashSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberProfileService {
	private final AccountGateService accountGates;
	private final MemberRepository members;
	private final WorkerCommandService workers;
	private final MemberApplicationService queries;

    @Transactional
    public MyMemberResponse updateProfile(
            Long memberId, UpdateMyMemberRequest request) {
        accountGates.requireActive(memberId);
        Member member = members.findByIdForUpdate(memberId)
                .filter(value -> value.getStatus() == MemberStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.ACTIVE_MEMBER_NOT_FOUND));
        validateRole(member, request);
        if (request.name() != null) member.updateName(request.name());
        if (request.workerProfile() != null) updateWorker(member.getWorkerProfile(), request.workerProfile());
        if (request.ownerProfile() != null) {
            var owner = member.getOwnerProfile();
            var update = request.ownerProfile();
            owner.updateStore(update.storeName(), update.businessType());
            updateLocation(owner.getStoreLocation(), update.storeLocation());
        }
        return queries.getMyMember(memberId);
    }

    private void validateRole(Member member, UpdateMyMemberRequest request) {
        if ((request.workerProfile() != null && member.getWorkerProfile() == null)
                || (request.ownerProfile() != null && member.getOwnerProfile() == null)) {
            throw new BusinessException(MemberErrorCode.ROLE_MISMATCH);
        }
    }

    private void updateWorker(WorkerProfile worker, UpdateWorkerProfileRequest request) {
        worker.updatePreferences(request.desiredHourlyWage(), request.activityRadiusKm(), request.immediatelyAvailable());
        updateLocation(worker.getBaseLocation(), request.baseLocation());
        if (request.preferredBusinessTypes() != null) updateBusinessTypes(worker, request);
        if (request.availableTimes() != null) updateAvailableTimes(worker, request);
    }

    private void updateBusinessTypes(WorkerProfile worker, UpdateWorkerProfileRequest request) {
        var desired = new HashSet<>(request.preferredBusinessTypes());
        if (desired.size() != request.preferredBusinessTypes().size()) {
            throw new BusinessException(WorkerErrorCode.DUPLICATE_BUSINESS_TYPE);
        }
        worker.getPreferredBusinessTypes().removeIf(value -> !desired.contains(value.getBusinessType()));
        var existing = worker.getPreferredBusinessTypes().stream().map(value -> value.getBusinessType()).toList();
        request.preferredBusinessTypes().stream().filter(value -> !existing.contains(value))
                .forEach(worker::addPreferredBusinessType);
    }

    private void updateAvailableTimes(WorkerProfile worker, UpdateWorkerProfileRequest request) {
        var desired = new HashSet<>(request.availableTimes());
        if (desired.size() != request.availableTimes().size()
                || desired.stream().anyMatch(value -> !value.startTime().isBefore(value.endTime()))) {
            throw new BusinessException(WorkerErrorCode.INVALID_AVAILABLE_TIME);
        }
        worker.getAvailableTimes().removeIf(value -> !desired.contains(toRequest(value)));
        var existing = worker.getAvailableTimes().stream().map(this::toRequest).toList();
        desired.stream().filter(value -> !existing.contains(value)).forEach(value ->
                workers.addAvailableTime(worker, value.dayOfWeek(), value.startTime(), value.endTime()));
    }

    private WorkerAvailableTimeRequest toRequest(WorkerAvailableTime value) {
        return new WorkerAvailableTimeRequest(value.getDayOfWeek(), value.getStartTime(), value.getEndTime());
    }

    private void updateLocation(Location location, LocationRequest request) {
        if (request != null) location.update(request.address(), request.detailAddress(), request.latitude(), request.longitude());
    }
}
