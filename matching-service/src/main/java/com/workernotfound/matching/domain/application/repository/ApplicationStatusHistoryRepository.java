package com.workernotfound.matching.domain.application.repository;

import com.workernotfound.matching.domain.application.entity.ApplicationStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApplicationStatusHistoryRepository extends JpaRepository<ApplicationStatusHistory, Long> {

	List<ApplicationStatusHistory> findByApplicationIdOrderByRevisionAsc(Long applicationId);
}
