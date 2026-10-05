package com.workernotfound.chat.global.account;

import com.workernotfound.chat.global.exception.BusinessException;
import com.workernotfound.chat.global.exception.GlobalErrorCode;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class AccountGateService {
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
