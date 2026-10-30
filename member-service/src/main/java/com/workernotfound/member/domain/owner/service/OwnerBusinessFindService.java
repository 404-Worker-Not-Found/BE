package com.workernotfound.member.domain.owner.service;

import com.workernotfound.member.domain.member.dto.response.OwnerBusinessResponse;
import com.workernotfound.member.domain.member.entity.enums.MemberRole;
import com.workernotfound.member.domain.member.service.MemberFindService;
import com.workernotfound.member.domain.owner.exception.OwnerErrorCode;
import com.workernotfound.member.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerBusinessFindService {
    private final MemberFindService members;
    private final OwnerFindService owners;

    public OwnerBusinessResponse findBusiness(Long memberId) {
        var member = members.findActiveMember(memberId);
        if (member.getRole() != MemberRole.OWNER) {
            throw new BusinessException(OwnerErrorCode.OWNER_PROFILE_NOT_FOUND);
        }
        var profile = owners.findOwnerProfile(memberId);
        return new OwnerBusinessResponse(profile.getId(), member.getId());
    }
}
