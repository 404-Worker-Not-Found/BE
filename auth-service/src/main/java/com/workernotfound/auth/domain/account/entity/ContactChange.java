package com.workernotfound.auth.domain.account.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "contact_changes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContactChange {
    @Id private String id;
    @Column(nullable = false) private Long accountId;
    @Column(nullable = false) private Long memberId;
    @Column(nullable = false) private String channel;
    @Column(nullable = false) private String fingerprint;
    private String target;
    private String previousEmail;
    @Column(nullable = false) private String status;
    @Column(nullable = false) private LocalDateTime createdAt;
    @Column(nullable = false) private LocalDateTime nextAttemptAt;
    @Column(nullable = false) private int attempts;
    public void claim() {
        attempts++;
        nextAttemptAt = LocalDateTime.now().plusSeconds(Math.min(300, 15L << Math.min(attempts - 1, 5)));
    }
    @Builder
    private ContactChange(String id, Long accountId, Long memberId, String channel,
            String fingerprint, String target, String previousEmail) {
        this.id = id; this.accountId = accountId; this.memberId = memberId;
        this.channel = channel; this.fingerprint = fingerprint; this.target = target;
        this.previousEmail = previousEmail; status = "PENDING"; createdAt = LocalDateTime.now(); nextAttemptAt = createdAt;
    }
    public void finish(String status) {
        this.status = status; target = null; previousEmail = null;
    }
}
