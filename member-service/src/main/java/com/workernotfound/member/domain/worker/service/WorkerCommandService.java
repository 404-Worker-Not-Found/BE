package com.workernotfound.member.domain.worker.service;

import com.workernotfound.member.domain.location.entity.Location;
import com.workernotfound.member.domain.member.entity.Member;
import com.workernotfound.member.domain.worker.entity.WorkerAvailableTime;
import com.workernotfound.member.domain.worker.entity.WorkerProfile;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class WorkerCommandService {

	public WorkerProfile createWorkerProfile(
		Member member,
		Integer desiredHourlyWage,
		Integer activityRadiusKm,
		boolean immediatelyAvailable,
		Location baseLocation
	) {
		return WorkerProfile.builder()
			.member(member)
			.desiredHourlyWage(desiredHourlyWage)
			.activityRadiusKm(activityRadiusKm)
			.immediatelyAvailable(immediatelyAvailable)
			.baseLocation(baseLocation)
			.build();
	}

	public void addPreferredBusinessTypes(WorkerProfile workerProfile, Iterable<String> businessTypes) {
		Set<String> uniqueBusinessTypes = new HashSet<>();
		for (String businessType : businessTypes) {
			if (!uniqueBusinessTypes.add(businessType)) {
				throw new IllegalArgumentException("선호 업종이 중복되었습니다.");
			}
			workerProfile.addPreferredBusinessType(businessType);
		}
	}

	public void addAvailableTime(
		WorkerProfile workerProfile,
		DayOfWeek dayOfWeek,
		LocalTime startTime,
		LocalTime endTime
	) {
		validateAvailableTime(startTime, endTime);
		WorkerAvailableTime availableTime = WorkerAvailableTime.builder()
			.workerProfile(workerProfile)
			.dayOfWeek(dayOfWeek)
			.startTime(startTime)
			.endTime(endTime)
			.build();
		workerProfile.addAvailableTime(availableTime);
	}

	private void validateAvailableTime(LocalTime startTime, LocalTime endTime) {
		if (!startTime.isBefore(endTime)) {
			throw new IllegalArgumentException("근무 시작 시간은 종료 시간보다 빨라야 합니다.");
		}
	}
}
