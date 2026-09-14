package com.workernotfound.work.domain.work.repository;

import com.workernotfound.work.domain.work.entity.Work;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkRepository extends JpaRepository<Work, Long> {
  @Query("select w.matchingId from Work w where w.id = :id")
  Optional<Long> findMatchingIdById(@Param("id") Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select w from Work w where w.id = :id")
  Optional<Work> findByIdForUpdate(@Param("id") Long id);
}
