package com.workernotfound.member.domain.location.repository;

import com.workernotfound.member.domain.location.entity.Location;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationRepository extends JpaRepository<Location, Long> {
}
