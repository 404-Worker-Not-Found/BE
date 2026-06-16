package com.workernotfound.member.domain.owner.repository;

import com.workernotfound.member.domain.owner.entity.OwnerProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OwnerProfileRepository extends JpaRepository<OwnerProfile, Long> {

	Optional<OwnerProfile> findByMember_Id(Long memberId);

	boolean existsByMember_Id(Long memberId);
}
