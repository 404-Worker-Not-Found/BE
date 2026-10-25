package com.workernotfound.payment.global.account;

import com.workernotfound.payment.global.exception.*;
import com.workernotfound.payment.global.exception.BusinessException;
import com.workernotfound.payment.global.exception.GlobalErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class AccountGateService {
  // 예치 총액이 지급 완료와 환불 완료로 모두 끝났고 차단되지 않은 예치다.
  // 잔액 CHECK로 이때 가용·잠금·정산 예약·환불 예약은 모두 0이다.
  private static final String CLOSED_DEPOSIT =
      "(d.funding_blocked=false and d.deposited_amount=d.settlement_paid_amount+d.refunded_amount)";
  private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireActive(Long... memberIds) {
        Arrays.stream(memberIds).filter(Objects::nonNull).distinct().sorted().forEach(id -> {
            if (!lock(id).state().equals("ACTIVE")) throw new BusinessException(GlobalErrorCode.FORBIDDEN);
        });
    }

    public boolean isActive(Long id) {
        var states = jdbc.queryForList("select state from account_gates where member_id=?", String.class, id);
        return states.isEmpty() || states.get(0).equals("ACTIVE");
    }

    @Transactional
    public Result transition(Long id, String commandId, String action) {
        Gate gate = lock(id);
        var receipts = jdbc.queryForList("select state from account_withdrawal_receipts where member_id=? and command_id=?", String.class, id, commandId);
        String previous = receipts.isEmpty() ? null : receipts.get(0);
        String state = decide(id, commandId, action, gate, previous);
        if (Objects.equals(state, previous)) return new Result(id, commandId, state);
        if (state.equals("PREPARED")) jdbc.update("update account_gates set state='PREPARED', command_id=? where member_id=?", commandId, id);
        if (state.equals("RELEASED") && commandId.equals(gate.command())) jdbc.update("update account_gates set state='ACTIVE', command_id=null where member_id=?", id);
        if (state.equals("COMMITTED")) jdbc.update("update account_gates set state='WITHDRAWN' where member_id=?", id);
        jdbc.update("insert into account_withdrawal_receipts(member_id,command_id,state) values(?,?,?) on duplicate key update state=?", id, commandId, state, state);
        return new Result(id, commandId, state);
    }

    private String decide(Long id, String command, String action, Gate gate, String previous) {
        if (action.equals("prepare")) {
            if (previous != null) return previous;
            return gate.state().equals("ACTIVE") && !hasBlockers(id) ? "PREPARED" : "REJECTED";
        }
        if (action.equals("release")) {
            if ("COMMITTED".equals(previous)) throw new BusinessException(GlobalErrorCode.FORBIDDEN);
            return "RELEASED";
        }
        if (action.equals("commit") && "COMMITTED".equals(previous)) return previous;
        if (!action.equals("commit") || !"PREPARED".equals(previous) || !command.equals(gate.command())) {
            throw new BusinessException(GlobalErrorCode.FORBIDDEN);
        }
        return "COMMITTED";
    }

    private boolean hasBlockers(Long id) {
        // DEPOSITED 주문은 그 공고의 예치가 종료됐을 때만 차단하지 않는다. 다른 미종료 상태는 계속 차단한다.
        if (jdbc.queryForObject("select count(*) from payment_orders o where o.owner_member_id=? and o.status not in ('FAILED','SUPERSEDED')"
            + " and not (o.status='DEPOSITED' and exists (select 1 from payment_deposits d where d.job_post_id=o.job_post_id"
            + " and d.owner_member_id=o.owner_member_id and " + CLOSED_DEPOSIT + "))", Long.class, id) > 0) return true;
        if (jdbc.queryForObject("select count(*) from payment_locks where (owner_member_id=? or worker_member_id=?) and status='LOCKED'", Long.class, id, id) > 0) return true;
        if (jdbc.queryForObject("select count(*) from payment_deposits d where d.owner_member_id=? and not " + CLOSED_DEPOSIT, Long.class, id) > 0) return true;
        // 정산으로 이관된 잠금은 LOCKED가 아니므로 지급 완료 전 정산으로 계속 차단한다.
        if (jdbc.queryForObject("select count(*) from payment_settlements where (owner_member_id=? or worker_member_id=?) and status<>'PAID'", Long.class, id, id) > 0) return true;
        // PG 결과가 확정되지 않은 환불은 예치 차단과 별개로 계속 차단한다.
        if (jdbc.queryForObject("select count(*) from payment_refunds where owner_member_id=? and status='REQUESTED'", Long.class, id) > 0) return true;
        return false;
    }

    private Gate lock(Long id) {
        jdbc.update("insert into account_gates(member_id,state) values(?,'ACTIVE') on duplicate key update member_id=member_id", id);
        return jdbc.queryForObject("select state,command_id from account_gates where member_id=? for update",
                (row, index) -> new Gate(row.getString(1), row.getString(2)), id);
    }
    private record Gate(String state, String command) {}
    public record Result(Long memberId, String commandId, String state) {}
}

@Component
class AccountInternalAuthorization {
    @Value("${payment.internal.secret:}") private String secret;
    public void verify(String supplied) {
        if (secret.isBlank() || supplied == null || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(GlobalErrorCode.UNAUTHORIZED);
        }
    }
}
