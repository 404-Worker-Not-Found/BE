package com.workernotfound.auth.domain.account.service;

import com.workernotfound.auth.domain.account.entity.*;
import com.workernotfound.auth.domain.account.entity.enums.MemberStatus;
import com.workernotfound.auth.domain.account.repository.*;
import com.workernotfound.auth.domain.account.dto.*;
import com.workernotfound.auth.domain.auth.service.VerificationService;
import com.workernotfound.auth.domain.auth.exception.AuthErrorCode;
import com.workernotfound.auth.domain.token.repository.RefreshTokenRepository;
import com.workernotfound.auth.domain.token.service.AuthTokenClaims;
import com.workernotfound.auth.global.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContactChangeTransactionService {
    private final AuthAccountRepository accounts;
    private final ContactChangeRepository commands;
    private final VerificationService verification;
    private final RefreshTokenRepository tokens;
    private final jakarta.persistence.EntityManager entityManager;

    @Transactional
    public AccountChangeResponse submit(AuthTokenClaims claims, String id, ContactChangeRequest request) {
        AuthAccount account = accounts.findByIdForUpdate(claims.authAccountId()).orElseThrow(this::conflict);
        if (!account.getMemberId().equals(claims.memberId())) throw conflict();
        String fingerprint = fingerprint(request.channel() + ":" + request.target());
        var existing = commands.findById(id);
        if (existing.isPresent()) return replay(existing.get(), account, fingerprint);
        if (account.getStatus() != MemberStatus.ACTIVE
                || commands.existsByAccountIdAndStatus(account.getId(), "PENDING")) throw conflict();
        boolean email = request.channel() == ContactChangeRequest.Channel.EMAIL;
        if (email && accounts.findByEmail(request.target()).filter(other -> !other.getId().equals(account.getId())).isPresent()) throw conflict();
        if (!verification.consumeContactCode(email, request.target(), request.verificationCode())) {
            throw new BusinessException(AuthErrorCode.INVALID_CONTACT_CODE);
        }
        commands.save(ContactChange.builder().id(id).accountId(account.getId()).memberId(account.getMemberId())
                .channel(request.channel().name()).fingerprint(fingerprint).target(request.target())
                .previousEmail(account.getEmail()).build());
        account.beginEmailChange(email ? request.target() : account.getEmail());
        tokens.findAllByAuthAccountIdAndRevokedAtIsNull(account.getId()).forEach(token -> token.revoke(LocalDateTime.now()));
        return new AccountChangeResponse(id, "PENDING");
    }

    public record DispatchCommand(String id, Long memberId, String channel, String target) {}

    @Transactional
    public java.util.Optional<DispatchCommand> claim(String id) {
        var command = commands.findByIdForUpdate(id).orElseThrow();
        if (!"PENDING".equals(command.getStatus()) || command.getNextAttemptAt().isAfter(LocalDateTime.now())) {
            return java.util.Optional.empty();
        }
        command.claim();
        return java.util.Optional.of(new DispatchCommand(command.getId(), command.getMemberId(), command.getChannel(), command.getTarget()));
    }

    @Transactional
    public void complete(String id, boolean rejected) {
        ContactChange snapshot = commands.findById(id).orElseThrow();
        AuthAccount account = accounts.findByIdForUpdate(snapshot.getAccountId()).orElseThrow();
        ContactChange command = commands.findByIdForUpdate(id).orElseThrow();
        commands.flush();
        entityManager.refresh(command, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (!"PENDING".equals(command.getStatus())) return;
        if (rejected) account.restoreEmail(command.getPreviousEmail());
        else account.finishContactChange();
        command.finish(rejected ? "REJECTED" : "SUCCEEDED");
    }

    @Transactional(readOnly = true)
    public AccountChangeResponse getCommand(AuthTokenClaims claims, String id) {
        var command = commands.findById(id).filter(value -> value.getAccountId().equals(claims.authAccountId())
                && value.getMemberId().equals(claims.memberId())).orElseThrow(this::conflict);
        return new AccountChangeResponse(id, command.getStatus());
    }

    private AccountChangeResponse replay(ContactChange command, AuthAccount account, String fingerprint) {
        if (!command.getAccountId().equals(account.getId()) || !command.getFingerprint().equals(fingerprint)) throw conflict();
        return new AccountChangeResponse(command.getId(), command.getStatus());
    }

    private String fingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception); }
    }
    private BusinessException conflict() { return new BusinessException(AuthErrorCode.ACCOUNT_CHANGE_CONFLICT); }
}
