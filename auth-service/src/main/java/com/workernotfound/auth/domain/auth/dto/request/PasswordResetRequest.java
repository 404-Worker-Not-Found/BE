package com.workernotfound.auth.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PasswordResetRequest(
	@NotBlank @Email @Size(max = 255) String email,
	@NotBlank @Pattern(regexp = "[0-9]{6}") String verificationCode,
	@NotBlank @Size(min = 8, max = 100) String newPassword
) {
}
