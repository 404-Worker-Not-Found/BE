package com.workernotfound.member.domain.member.service;

import com.workernotfound.member.domain.member.dto.request.VerifiedContactChangeRequest;
import com.workernotfound.member.domain.member.entity.Member;
import com.workernotfound.member.domain.member.entity.enums.MemberRole;
import com.workernotfound.member.domain.member.repository.MemberRepository;
import com.workernotfound.member.global.exception.BusinessException;
import com.workernotfound.member.support.IntegrationTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

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
