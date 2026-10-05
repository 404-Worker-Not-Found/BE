package com.workernotfound.auth.domain.account.service;

import com.workernotfound.auth.domain.account.entity.enums.MemberStatus;
import com.workernotfound.auth.domain.account.repository.AuthAccountRepository;
import com.workernotfound.auth.domain.auth.exception.AuthErrorCode;
import com.workernotfound.auth.domain.token.repository.RefreshTokenRepository;
import com.workernotfound.auth.domain.token.service.AuthTokenClaims;
import com.workernotfound.auth.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class WithdrawalTransactionService {
    private final AuthAccountRepository accounts;
    private final RefreshTokenRepository tokens;
    private final JdbcTemplate jdbc;
    private final com.workernotfound.auth.domain.auth.service.VerificationService verification;
    private final jakarta.persistence.EntityManager entityManager;
    public record State(String commandId, Long accountId, Long memberId, String state, String blockedService) {}
    public record Claim(State command, String lease) {}

    @Transactional
    public State submit(AuthTokenClaims claims, String key) {
        var account = accounts.findByIdForUpdate(claims.authAccountId()).orElseThrow(this::conflict);
        var existing = find(key);
        if (existing.isPresent()) return owned(existing.get(), claims);
        if (account.getStatus() != MemberStatus.ACTIVE || !account.getMemberId().equals(claims.memberId())) throw conflict();
        jdbc.update("insert into account_withdrawals(command_id,account_id,member_id,state) values(?,?,?,'CHECKING')", key, account.getId(), account.getMemberId());
        account.prepareWithdrawal();
        tokens.findAllByAuthAccountIdAndRevokedAtIsNull(account.getId()).forEach(token -> token.revoke(LocalDateTime.now()));
        return find(key).orElseThrow();
    }

    @Transactional
    public Optional<Claim> claim(String key) {
        var state = jdbc.queryForObject("select command_id,account_id,member_id,state,blocked_service from account_withdrawals where command_id=? for update", (row,index) -> map(row), key);
        if (Set.of("SUCCEEDED", "REJECTED").contains(state.state())) return Optional.empty();
        int attempts = jdbc.queryForObject("select attempts from account_withdrawals where command_id=?", Integer.class, key);
        String lease = UUID.randomUUID().toString();
        int claimed = jdbc.update("update account_withdrawals set lease_token=?,lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 180 SECOND),attempts=attempts+1,next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL ? SECOND) where command_id=? and next_attempt_at<=CURRENT_TIMESTAMP(6) and (lease_until is null or lease_until<=CURRENT_TIMESTAMP(6))", lease, Math.min(300,15L << Math.min(attempts,5)), key);
        return claimed == 1 ? Optional.of(new Claim(state, lease)) : Optional.empty();
    }

    @Transactional
    public void advance(Claim claim, String state, String blockedService) {
        int changed = jdbc.update("update account_withdrawals set state=?,blocked_service=? where command_id=? and lease_token=? and lease_until>CURRENT_TIMESTAMP(6)", state, blockedService, claim.command().commandId(), claim.lease());
        if (changed != 1) throw new IllegalStateException("탈퇴 처리 임대가 만료되었습니다.");
    }

    @Transactional
    public void finish(Claim claim, boolean rejected) {
        var account = accounts.findByIdForUpdate(claim.command().accountId()).orElseThrow();
        entityManager.refresh(account, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        var command = jdbc.queryForObject("select command_id,account_id,member_id,state,blocked_service from account_withdrawals where command_id=? and lease_token=? and lease_until>CURRENT_TIMESTAMP(6) for update", (row,index) -> map(row), claim.command().commandId(), claim.lease());
        if (rejected) account.cancelWithdrawal();
        else {
            verification.eraseAccountVerification(account.getEmail());
            jdbc.update("delete from refresh_tokens where auth_account_id=?", account.getId());
            jdbc.update("delete from local_credentials where auth_account_id=?", account.getId());
            jdbc.update("delete from oauth_connections where auth_account_id=?", account.getId());
            jdbc.update("delete from contact_changes where account_id=?", account.getId());
            account.erasePersonalData();
        }
        jdbc.update("update account_withdrawals set state=?,completed_at=CURRENT_TIMESTAMP(6),lease_token=null,lease_until=null where command_id=?", rejected ? "REJECTED" : "SUCCEEDED", command.commandId());
    }

    @Transactional
    public void releaseLease(Claim claim) {
        jdbc.update("update account_withdrawals set lease_token=null,lease_until=null where command_id=? and lease_token=?", claim.command().commandId(), claim.lease());
    }
    public List<String> due() {
        return jdbc.queryForList("select command_id from account_withdrawals where state not in ('SUCCEEDED','REJECTED') and next_attempt_at<=CURRENT_TIMESTAMP(6) and (lease_until is null or lease_until<=CURRENT_TIMESTAMP(6)) order by next_attempt_at limit 20", String.class);
    }
    public State get(AuthTokenClaims claims, String key) { return owned(find(key).orElseThrow(this::conflict), claims); }
    private State owned(State state, AuthTokenClaims claims) {
        if (!state.accountId().equals(claims.authAccountId()) || !state.memberId().equals(claims.memberId())) throw conflict();
        return state;
    }
    private Optional<State> find(String key) {
        return jdbc.query("select command_id,account_id,member_id,state,blocked_service from account_withdrawals where command_id=?", (row,index) -> map(row), key).stream().findFirst();
    }
    private State map(java.sql.ResultSet row) throws java.sql.SQLException {
        return new State(row.getString(1), row.getLong(2), row.getLong(3), row.getString(4), row.getString(5));
    }
    private BusinessException conflict() { return new BusinessException(AuthErrorCode.ACCOUNT_CHANGE_CONFLICT); }
}
