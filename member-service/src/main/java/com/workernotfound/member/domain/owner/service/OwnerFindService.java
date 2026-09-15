package com.workernotfound.member.domain.owner.service;

import com.workernotfound.member.domain.owner.entity.OwnerProfile;
import com.workernotfound.member.domain.owner.exception.OwnerErrorCode;
import com.workernotfound.member.domain.owner.repository.OwnerProfileRepository;
import com.workernotfound.member.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerFindService {

	private final OwnerProfileRepository ownerProfileRepository;

	public OwnerProfile findOwnerProfile(Long memberId) {
		return ownerProfileRepository
				.findByMember_Id(memberId)
				.orElseThrow(() -> new BusinessException(OwnerErrorCode.OWNER_PROFILE_NOT_FOUND));
	}
}
