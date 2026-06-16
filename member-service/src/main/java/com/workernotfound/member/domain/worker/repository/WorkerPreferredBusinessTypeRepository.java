package com.workernotfound.member.domain.worker.repository;

import com.workernotfound.member.domain.worker.entity.WorkerPreferredBusinessType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerPreferredBusinessTypeRepository extends JpaRepository<WorkerPreferredBusinessType, Long> {

	List<WorkerPreferredBusinessType> findAllByWorkerProfile_Id(Long workerProfileId);

	boolean existsByWorkerProfile_IdAndBusinessType(Long workerProfileId, String businessType);

	void deleteAllByWorkerProfile_Id(Long workerProfileId);
}
