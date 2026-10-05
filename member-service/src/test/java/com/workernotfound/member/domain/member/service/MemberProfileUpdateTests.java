package com.workernotfound.member.domain.member.service;

import com.workernotfound.member.domain.member.dto.request.*;
import com.workernotfound.member.domain.member.dto.request.UpdateMyMemberRequest.UpdateOwnerProfileRequest;
import com.workernotfound.member.domain.member.dto.request.UpdateMyMemberRequest.UpdateWorkerProfileRequest;
import com.workernotfound.member.domain.member.dto.request.VerifiedContactChangeRequest;
import com.workernotfound.member.domain.member.entity.Member;
import com.workernotfound.member.domain.member.entity.enums.*;
import com.workernotfound.member.domain.member.entity.enums.MemberRole;
import com.workernotfound.member.domain.member.repository.MemberRepository;
import com.workernotfound.member.global.account.AccountGateService;
import com.workernotfound.member.global.exception.BusinessException;
import com.workernotfound.member.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@Transactional
class MemberProfileUpdateTests extends IntegrationTestSupport {
    @Autowired MemberApplicationService members;
    @Autowired MemberProfileService profiles;
    @Autowired MemberRepository repository;
    @Autowired EntityManager entityManager;

    @Test
    void partialUpdatePreservesContactsAndOtherPreferences() {
        long id = create("partial");
        profiles.updateProfile(id, new UpdateMyMemberRequest("새 이름",
                new UpdateWorkerProfileRequest(15000, null, false, null, null, null), null));
        entityManager.flush(); entityManager.clear();
        var response = members.getMyMember(id);
        assertThat(response.name()).isEqualTo("새 이름");
        assertThat(response.email()).isEqualTo("partial@example.com");
        assertThat(response.workerProfile().desiredHourlyWage()).isEqualTo(15000);
        assertThat(response.workerProfile().activityRadiusKm()).isEqualTo(5);
        assertThat(response.workerProfile().immediatelyAvailable()).isFalse();
    }
    @Test
    void replacementKeepsExistingUniqueChildrenAndDeletesRemovedOnes() {
        long id = create("replace");
        var time = new WorkerAvailableTimeRequest(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(18, 0));
        var update = new UpdateMyMemberRequest(null, new UpdateWorkerProfileRequest(null, null, null, null,
                List.of("CAFE", "RETAIL"), List.of(time)), null);
        profiles.updateProfile(id, update);
        entityManager.flush(); entityManager.clear();
        profiles.updateProfile(id, update);
        entityManager.flush(); entityManager.clear();
        var response = members.getMyMember(id).workerProfile();
        assertThat(response.preferredBusinessTypes()).containsExactlyInAnyOrder("CAFE", "RETAIL");
        assertThat(response.availableTimes()).hasSize(1);
    }
    @Test
    void wrongRoleDoesNotMutateBasicInformation() {
        long id = create("wrongrole");
        assertThatThrownBy(() -> profiles.updateProfile(id, new UpdateMyMemberRequest("변경 금지", null,
                new UpdateOwnerProfileRequest("가게", null, null))))
                .isInstanceOf(BusinessException.class);
        assertThat(repository.findById(id).orElseThrow().getName()).isEqualTo("워커");
    }
    @Test
    void duplicateTimesAreRejected() {
        long id = create("duplicate");
        var time = new WorkerAvailableTimeRequest(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(18, 0));
        assertThatThrownBy(() -> profiles.updateProfile(id, new UpdateMyMemberRequest(null,
                new UpdateWorkerProfileRequest(null, null, null, null, null, List.of(time, time)), null)))
                .isInstanceOf(BusinessException.class);
    }
    private long create(String prefix) {
        return members.createWorkerMember(new CreateWorkerMemberRequest("워커", prefix + "@example.com",
                "010" + String.format("%08d", Math.abs(prefix.hashCode()) % 100000000), MemberRole.WORKER,
                12000, 5, true, new LocationRequest("서울", null, BigDecimal.valueOf(37), BigDecimal.valueOf(127)),
                List.of("CAFE", "RESTAURANT"), List.of(new WorkerAvailableTimeRequest(
                DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(18, 0))))).memberId();
    }
}

@Transactional
class MemberContactChangeTests extends IntegrationTestSupport {
    @Autowired MemberContactService contacts;
    @Autowired MemberRepository members;
    @Test
    void replayCannotUndoALaterChange() {
        var member = save("original");
        String first = UUID.randomUUID().toString();
        var request = email("first@example.com");
        assertThat(contacts.changeContact(member.getId(), first, request).accepted()).isTrue();
        contacts.changeContact(member.getId(), UUID.randomUUID().toString(), email("second@example.com"));
        assertThat(contacts.changeContact(member.getId(), first, request).accepted()).isTrue();
        assertThat(member.getEmail()).isEqualTo("second@example.com");
    }
    @Test
    void rejectionRemainsRejectedEvenAfterTargetBecomesAvailable() {
        var member = save("requester");
        var other = save("occupied");
        String id = UUID.randomUUID().toString();
        var request = email(other.getEmail());
        assertThat(contacts.changeContact(member.getId(), id, request).accepted()).isFalse();
        contacts.changeContact(other.getId(), UUID.randomUUID().toString(), email("free@example.com"));
        assertThat(contacts.changeContact(member.getId(), id, request).accepted()).isFalse();
        assertThat(member.getEmail()).isEqualTo("requester@example.com");
    }
    @Test
    void keyReuseWithDifferentTargetFails() {
        var member = save("reuse");
        String id = UUID.randomUUID().toString();
        contacts.changeContact(member.getId(), id, email("one@example.com"));
        assertThatThrownBy(() -> contacts.changeContact(member.getId(), id, email("two@example.com")))
                .isInstanceOf(BusinessException.class);
    }
    private Member save(String prefix) {
        return members.saveAndFlush(Member.builder().name("회원").email(prefix + "@example.com")
                .phoneNumber("010" + String.format("%08d", Math.abs(prefix.hashCode()) % 100000000))
                .role(MemberRole.WORKER).build());
    }
    private VerifiedContactChangeRequest email(String target) {
        return new VerifiedContactChangeRequest(VerifiedContactChangeRequest.Channel.EMAIL, target);
    }
}

@Transactional
class MemberWithdrawalTests extends IntegrationTestSupport {
    @Autowired MemberApplicationService application;
    @Autowired MemberRepository members;
    @Autowired AccountGateService gates;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
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
