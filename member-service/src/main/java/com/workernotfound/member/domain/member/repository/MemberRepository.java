package com.workernotfound.member.domain.member.repository;

import com.workernotfound.member.domain.member.entity.Member;
import com.workernotfound.member.domain.member.entity.enums.MemberStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {

	Optional<Member> findByEmail(String email);

	Optional<Member> findByPhoneNumber(String phoneNumber);

	Optional<Member> findByIdAndStatus(Long id, MemberStatus status);

	boolean existsByEmail(String email);

	boolean existsByPhoneNumber(String phoneNumber);
}
