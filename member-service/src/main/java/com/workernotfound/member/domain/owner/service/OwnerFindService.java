package com.workernotfound.member.domain.owner.service;

import com.workernotfound.member.domain.owner.entity.OwnerProfile;
import com.workernotfound.member.domain.owner.repository.OwnerProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerFindService {

	private final OwnerProfileRepository ownerProfileRepository;

	public OwnerProfile findOwnerProfile(Long memberId) {
		return ownerProfileRepository.findByMember_Id(memberId)
			.orElseThrow(() -> new IllegalArgumentException("사업자 프로필을 찾을 수 없습니다."));
	}
}
