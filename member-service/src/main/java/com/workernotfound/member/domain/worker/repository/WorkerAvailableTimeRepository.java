package com.workernotfound.member.domain.worker.repository;

import com.workernotfound.member.domain.worker.entity.WorkerAvailableTime;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerAvailableTimeRepository extends JpaRepository<WorkerAvailableTime, Long> {

	List<WorkerAvailableTime> findAllByWorkerProfile_Id(Long workerProfileId);

	boolean existsByWorkerProfile_IdAndDayOfWeekAndStartTimeAndEndTime(
		Long workerProfileId,
		DayOfWeek dayOfWeek,
		LocalTime startTime,
		LocalTime endTime
	);

	void deleteAllByWorkerProfile_Id(Long workerProfileId);
}
