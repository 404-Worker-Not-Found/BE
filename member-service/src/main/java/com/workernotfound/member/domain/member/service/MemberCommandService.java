package com.workernotfound.member.domain.member.service;

import com.workernotfound.member.domain.member.entity.Member;
import com.workernotfound.member.domain.member.entity.enums.MemberRole;
import com.workernotfound.member.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MemberCommandService {

	private final MemberRepository memberRepository;

	public Member createMember(String name, String email, String phoneNumber, MemberRole role) {
		validateEmailAvailable(email);
		validatePhoneNumberAvailable(phoneNumber);
		return Member.builder()
			.name(name)
			.email(email)
			.phoneNumber(phoneNumber)
			.role(role)
			.build();
	}

	public Member saveMember(Member member) {
		return memberRepository.save(member);
	}

	public void deleteMember(Member member) {
		memberRepository.delete(member);
	}

	private void validateEmailAvailable(String email) {
		if (memberRepository.existsByEmail(email)) {
			throw new IllegalArgumentException("이미 사용 중인 이메일입니다.");
		}
	}

	private void validatePhoneNumberAvailable(String phoneNumber) {
		if (memberRepository.existsByPhoneNumber(phoneNumber)) {
			throw new IllegalArgumentException("이미 사용 중인 휴대폰 번호입니다.");
		}
	}
}
