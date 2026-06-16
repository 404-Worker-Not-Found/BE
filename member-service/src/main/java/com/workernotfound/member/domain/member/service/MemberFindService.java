package com.workernotfound.member.domain.member.service;

import com.workernotfound.member.domain.member.entity.Member;
import com.workernotfound.member.domain.member.entity.enums.MemberStatus;
import com.workernotfound.member.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberFindService {

	private final MemberRepository memberRepository;

	public Member findMember(Long memberId) {
		return memberRepository.findById(memberId)
			.orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));
	}

	public Member findActiveMember(Long memberId) {
		return memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE)
			.orElseThrow(() -> new IllegalArgumentException("활성 회원을 찾을 수 없습니다."));
	}
}
