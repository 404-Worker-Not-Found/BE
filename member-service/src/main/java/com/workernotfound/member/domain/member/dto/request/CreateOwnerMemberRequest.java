package com.workernotfound.member.domain.member.dto.request;

import com.workernotfound.member.domain.member.entity.enums.MemberRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateOwnerMemberRequest(
	@NotBlank
	@Size(max = 50)
	String name,

	@NotBlank
	@Email
	@Size(max = 255)
	String email,

	@NotBlank
	@Pattern(regexp = "^\\+?[0-9]{10,15}$")
	String phoneNumber,

	@NotNull
	MemberRole role,

	@NotBlank
	@Pattern(regexp = "^[0-9]{10}$|^[0-9]{3}-[0-9]{2}-[0-9]{5}$")
	String businessRegistrationNumber,

	@NotBlank
	@Size(max = 100)
	String storeName,

	@NotBlank
	@Size(max = 100)
	String businessType,

	@Valid
	@NotNull
	LocationRequest storeLocation
) {
}
