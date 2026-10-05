package com.workernotfound.member.domain.member.service;
import com.workernotfound.member.support.IntegrationTestSupport;
import com.workernotfound.member.domain.member.dto.request.*;
import com.workernotfound.member.domain.member.entity.enums.*;
import com.workernotfound.member.domain.member.repository.MemberRepository;
import com.workernotfound.member.global.account.AccountGateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import java.util.*;
import java.math.BigDecimal;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
@Transactional
class MemberWithdrawalTests extends IntegrationTestSupport {
    @Autowired MemberApplicationService application;
    @Autowired MemberRepository members;
    @Autowired AccountGateService gates;
    @Autowired EntityManager entityManager;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Test
    void committedWithdrawalErasesWorkerGraphAndKeepsATombstone() {
        long id = worker("erase");
        long location = members.findById(id).orElseThrow().getWorkerProfile().getBaseLocation().getId();
        String key = UUID.randomUUID().toString();
        assertThat(gates.transition(id,key,"prepare").state()).isEqualTo("PREPARED");
        assertThat(gates.transition(id,key,"commit").state()).isEqualTo("COMMITTED");
        entityManager.flush(); entityManager.clear();
        var member = members.findById(id).orElseThrow();
        assertThat(member.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(member.getEmail()).isNull(); assertThat(member.getPhoneNumber()).isNull();
        assertThat(member.getWorkerProfile()).isNull(); assertThat(member.getWithdrawnAt()).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from locations where id=?",Long.class,location)).isZero();
        assertThat(gates.transition(id,key,"commit").state()).isEqualTo("COMMITTED");
    }
    @Test
    void oldReleaseCannotReactivateMemberDuringANewAttempt() {
        long id = worker("retry");
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        gates.transition(id,first,"prepare"); gates.transition(id,first,"release");
        gates.transition(id,second,"prepare"); gates.transition(id,first,"release");
        assertThat(members.findById(id).orElseThrow().getStatus()).isEqualTo(MemberStatus.WITHDRAWING);
        assertThat(gates.isActive(id)).isFalse();
    }
    private long worker(String prefix) {
        return application.createWorkerMember(new CreateWorkerMemberRequest("회원",prefix+"@example.com",
                "010"+String.format("%08d",Math.abs(prefix.hashCode())%100000000),MemberRole.WORKER,12000,5,true,
                new LocationRequest("서울",null,BigDecimal.valueOf(37),BigDecimal.valueOf(127)),List.of("CAFE"),
                List.of(new WorkerAvailableTimeRequest(DayOfWeek.MONDAY,LocalTime.of(9,0),LocalTime.of(18,0))))).memberId();
    }
}
