package com.workernotfound.member.domain.member.repository;

import com.workernotfound.member.domain.member.entity.Member;
import com.workernotfound.member.domain.member.entity.enums.MemberRole;
import com.workernotfound.member.domain.member.entity.enums.MemberStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select member from Member member where member.id = :id")
    Optional<Member> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

	Optional<Member> findByEmail(String email);

	Optional<Member> findByPhoneNumber(String phoneNumber);

	Optional<Member> findByIdAndStatus(Long id, MemberStatus status);

	List<Member> findByIdInAndRoleAndStatus(Collection<Long> ids, MemberRole role, MemberStatus status);

	boolean existsByEmail(String email);

	boolean existsByPhoneNumber(String phoneNumber);
}
