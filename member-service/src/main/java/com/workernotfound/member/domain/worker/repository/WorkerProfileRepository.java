package com.workernotfound.member.domain.worker.repository;

import com.workernotfound.member.domain.worker.entity.WorkerProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerProfileRepository extends JpaRepository<WorkerProfile, Long> {

	Optional<WorkerProfile> findByMember_Id(Long memberId);

	boolean existsByMember_Id(Long memberId);
}
