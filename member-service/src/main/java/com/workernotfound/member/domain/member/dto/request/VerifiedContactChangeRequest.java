package com.workernotfound.member.domain.member.dto.request;

import jakarta.validation.constraints.*;

public record VerifiedContactChangeRequest(
    @NotNull Channel channel, @NotBlank @Size(max = 255) String target
) {
    public enum Channel { EMAIL, PHONE }
    @AssertTrue(message = "연락처 형식이 올바르지 않습니다.")
    public boolean isTargetValid() {
        if (channel == null || target == null) return false;
        return channel == Channel.EMAIL ? target.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
                : target.matches("\\+?[0-9]{10,15}");
    }
}
