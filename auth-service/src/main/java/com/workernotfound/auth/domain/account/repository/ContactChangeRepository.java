package com.workernotfound.auth.domain.account.repository;

import com.workernotfound.auth.domain.account.entity.ContactChange;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContactChangeRepository extends JpaRepository<ContactChange, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select command from ContactChange command where command.id = :id")
    Optional<ContactChange> findByIdForUpdate(@Param("id") String id);
    boolean existsByAccountIdAndStatus(Long accountId, String status);
    List<ContactChange> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(String status, LocalDateTime now, Pageable page);
}
