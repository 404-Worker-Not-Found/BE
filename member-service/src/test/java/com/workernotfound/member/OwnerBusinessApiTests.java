package com.workernotfound.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.workernotfound.member.domain.location.entity.Location;
import com.workernotfound.member.domain.member.entity.Member;
import com.workernotfound.member.domain.member.entity.enums.MemberRole;
import com.workernotfound.member.domain.member.repository.MemberRepository;
import com.workernotfound.member.domain.owner.entity.OwnerProfile;
import com.workernotfound.member.domain.owner.entity.enums.BusinessVerificationStatus;
import com.workernotfound.member.domain.owner.repository.OwnerProfileRepository;
import com.workernotfound.member.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
class OwnerBusinessApiTests extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired OwnerProfileRepository owners;

    Member member(MemberRole role) {
        String suffix = UUID.randomUUID().toString();
        return members.saveAndFlush(Member.builder().name("등록 점주").email(suffix+"@example.com")
                .phoneNumber(suffix.substring(0,20)).role(role).build());
    }
    String path(Member member) { return "/api/members/internal/owners/"+member.getId()+"/business"; }

    @Test void returnsRegisteredProfileIdAndOwnerOnly() throws Exception {
        for (int i=0;i<5;i++) member(MemberRole.WORKER);
        var owner = member(MemberRole.OWNER);
        var profile = owners.saveAndFlush(OwnerProfile.builder().member(owner).storeName("가게").businessType("CAFE")
                .businessRegistrationNumber("123-45-67890").businessVerificationStatus(BusinessVerificationStatus.VERIFIED)
                .storeLocation(Location.builder().address("서울").latitude(BigDecimal.ONE).longitude(BigDecimal.ONE).build()).build());
        assertThat(profile.getId()).isNotEqualTo(owner.getId());
        mvc.perform(get(path(owner)).header("X-Internal-Secret","test-internal-secret"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.businessId").value(profile.getId()))
                .andExpect(jsonPath("$.data.ownerMemberId").value(owner.getId()))
                .andExpect(jsonPath("$.data.businessRegistrationNumber").doesNotExist());
    }
    @Test void requiresInternalSecret() throws Exception {
        mvc.perform(get(path(member(MemberRole.OWNER)))).andExpect(status().isUnauthorized());
    }
    @Test void workerAndOwnerWithoutProfileAreNotRegisteredBusinesses() throws Exception {
        for (var role : MemberRole.values()) {
            mvc.perform(get(path(member(role))).header("X-Internal-Secret","test-internal-secret"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("OWNER-404-001"));
        }
    }
    @Test void withdrawingAndMissingMembersAreNotEligible() throws Exception {
        var owner = member(MemberRole.OWNER);
        owner.prepareWithdrawal();members.flush();
        mvc.perform(get(path(owner)).header("X-Internal-Secret","test-internal-secret"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MEMBER-404-002"));
        mvc.perform(get("/api/members/internal/owners/9223372036854775807/business").header("X-Internal-Secret","test-internal-secret"))
                .andExpect(status().isNotFound());
    }
}
