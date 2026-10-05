package com.workernotfound.auth.domain.account.repository;

import com.workernotfound.auth.domain.account.entity.ContactChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.List;

public interface ContactChangeRepository extends JpaRepository<ContactChange, String> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select command from ContactChange command where command.id = :id")
    java.util.Optional<ContactChange> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") String id);
    boolean existsByAccountIdAndStatus(Long accountId, String status);
    List<ContactChange> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(String status, java.time.LocalDateTime now, Pageable page);
}
