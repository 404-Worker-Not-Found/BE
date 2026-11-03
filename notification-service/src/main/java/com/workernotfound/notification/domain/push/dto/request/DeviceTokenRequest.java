package com.workernotfound.notification.domain.push.dto.request;

import jakarta.validation.constraints.*;

public record DeviceTokenRequest(
    @NotBlank @Size(max = 4096) @Pattern(regexp = "[!-~]+") String token,
    @NotNull @Pattern(regexp = "ANDROID") String platform) {
  @Override public String toString() { return "DeviceTokenRequest[redacted]"; }
}
