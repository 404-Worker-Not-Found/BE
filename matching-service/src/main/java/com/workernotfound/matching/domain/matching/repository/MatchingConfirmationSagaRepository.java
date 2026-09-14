package com.workernotfound.matching.domain.matching.repository;

import com.workernotfound.matching.domain.matching.entity.MatchingConfirmationSaga;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface MatchingConfirmationSagaRepository extends JpaRepository<MatchingConfirmationSaga, Long> {

	Optional<MatchingConfirmationSaga> findByMatchingId(Long matchingId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select saga from MatchingConfirmationSaga saga where saga.matching.id = :matchingId")
	Optional<MatchingConfirmationSaga> findByMatchingIdForUpdate(Long matchingId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select saga from MatchingConfirmationSaga saga where saga.id = :sagaId")
	Optional<MatchingConfirmationSaga> findByIdForUpdate(Long sagaId);
}
