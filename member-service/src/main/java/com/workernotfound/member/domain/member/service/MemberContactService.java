package com.workernotfound.member.domain.member.service;

import com.workernotfound.member.domain.member.dto.request.VerifiedContactChangeRequest;
import com.workernotfound.member.domain.member.dto.response.ContactChangeResult;
import com.workernotfound.member.domain.member.entity.ContactChangeReceipt;
import com.workernotfound.member.domain.member.entity.enums.MemberStatus;
import com.workernotfound.member.domain.member.exception.MemberErrorCode;
import com.workernotfound.member.domain.member.repository.*;
import com.workernotfound.member.global.account.AccountGateService;
import com.workernotfound.member.global.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberContactService {
	private final AccountGateService accountGates;
	private final MemberRepository members;
	private final ContactChangeReceiptRepository receipts;

    @Transactional
    public ContactChangeResult changeContact(Long memberId, String id, VerifiedContactChangeRequest request) {
        accountGates.requireActive(memberId);
        var member = members.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
        String fingerprint = fingerprint(request.channel() + ":" + request.target());
        var receipt = receipts.findById(id);
        if (receipt.isPresent()) {
            if (!receipt.get().getMemberId().equals(memberId) || !receipt.get().getFingerprint().equals(fingerprint)) {
                throw new BusinessException(MemberErrorCode.CONTACT_CHANGE_CONFLICT);
            }
            return result(id, memberId, request, receipt.get().isAccepted());
        }
        boolean email = request.channel() == VerifiedContactChangeRequest.Channel.EMAIL;
        var duplicate = email ? members.findByEmail(request.target()) : members.findByPhoneNumber(request.target());
        boolean accepted = member.getStatus() == MemberStatus.ACTIVE
                && duplicate.filter(value -> !value.getId().equals(memberId)).isEmpty();
        if (accepted) member.updateContact(email, request.target());
        receipts.save(ContactChangeReceipt.builder().id(id).memberId(memberId).fingerprint(fingerprint).accepted(accepted).build());
        members.flush();
        return result(id, memberId, request, accepted);
    }

    private ContactChangeResult result(String id, Long memberId, VerifiedContactChangeRequest request, boolean accepted) {
        return new ContactChangeResult(id, memberId, request.channel().name(), request.target(), accepted);
    }
    private String fingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception); }
    }
}
