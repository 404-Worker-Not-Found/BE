package com.workernotfound.member.domain.member.service;

import com.workernotfound.member.domain.member.entity.Member;
import com.workernotfound.member.domain.member.entity.enums.MemberRole;
import com.workernotfound.member.domain.member.entity.enums.MemberStatus;
import com.workernotfound.member.domain.member.exception.MemberErrorCode;
import com.workernotfound.member.domain.member.repository.MemberRepository;
import com.workernotfound.member.global.exception.BusinessException;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberFindService {

	private final MemberRepository memberRepository;

	public Member findMember(Long memberId) {
		return memberRepository
				.findById(memberId)
				.orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
	}

	public Member findActiveMember(Long memberId) {
		return memberRepository
				.findByIdAndStatus(memberId, MemberStatus.ACTIVE)
				.orElseThrow(() -> new BusinessException(MemberErrorCode.ACTIVE_MEMBER_NOT_FOUND));
	}

	public List<Member> findActiveWorkers(Collection<Long> memberIds) {
		return memberRepository.findByIdInAndRoleAndStatus(
				memberIds, MemberRole.WORKER, MemberStatus.ACTIVE);
	}
}
