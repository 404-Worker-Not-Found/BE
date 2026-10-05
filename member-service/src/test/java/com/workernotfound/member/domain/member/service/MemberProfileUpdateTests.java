package com.workernotfound.member.domain.member.service;

import com.workernotfound.member.domain.member.dto.request.*;
import com.workernotfound.member.domain.member.entity.enums.MemberRole;
import com.workernotfound.member.domain.member.repository.MemberRepository;
import com.workernotfound.member.global.exception.BusinessException;
import com.workernotfound.member.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
