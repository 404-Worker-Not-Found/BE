package com.workernotfound.member.domain.member.entity;
import jakarta.persistence.*;
import lombok.*;
@Entity
@Table(name = "contact_change_receipts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContactChangeReceipt {
    @Id private String id;
    @Column(nullable = false) private Long memberId;
    @Column(nullable = false) private String fingerprint;
    @Column(nullable = false) private boolean accepted;
    @Builder
    private ContactChangeReceipt(String id, Long memberId, String fingerprint, boolean accepted) {
        this.id = id; this.memberId = memberId; this.fingerprint = fingerprint; this.accepted = accepted;
    }
}
