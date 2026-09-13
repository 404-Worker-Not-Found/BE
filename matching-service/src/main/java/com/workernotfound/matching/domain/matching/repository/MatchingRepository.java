package com.workernotfound.matching.domain.matching.repository;

import com.workernotfound.matching.domain.matching.entity.Matching;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchingRepository extends JpaRepository<Matching, Long> {

	Optional<Matching> findByApplicationId(Long applicationId);

	boolean existsByApplicationId(Long applicationId);
}
