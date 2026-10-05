package com.workernotfound.member.domain.member.service;
import com.workernotfound.member.domain.member.repository.MemberRepository;
import com.workernotfound.member.domain.member.exception.MemberErrorCode;
import com.workernotfound.member.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service
@RequiredArgsConstructor
public class MemberWithdrawalService {
    private final MemberRepository members;
    private final JdbcTemplate jdbc;
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isActive(Long id) { return require(id).getStatus() == com.workernotfound.member.domain.member.entity.enums.MemberStatus.ACTIVE; }
    @Transactional(propagation = Propagation.MANDATORY)
    public void prepare(Long id) { require(id).prepareWithdrawal(); }
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Long id) { require(id).releaseWithdrawal(); }
    @Transactional(propagation = Propagation.MANDATORY)
    public void erase(Long id) {
        var member = require(id);
        if (member.getWorkerProfile() != null) {
            member.getWorkerProfile().getPreferredBusinessTypes().size();
            member.getWorkerProfile().getAvailableTimes().size();
            member.getWorkerProfile().getBaseLocation();
        }
        if (member.getOwnerProfile() != null) member.getOwnerProfile().getStoreLocation();
        member.erasePersonalData();
        jdbc.update("delete from contact_change_receipts where member_id=?", id);
        members.flush();
    }
    private com.workernotfound.member.domain.member.entity.Member require(Long id) {
        return members.findByIdForUpdate(id).orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
    }
}
