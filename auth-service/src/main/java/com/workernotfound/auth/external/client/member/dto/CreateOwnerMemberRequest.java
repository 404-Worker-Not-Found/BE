package com.workernotfound.auth.external.client.member.dto;

public record CreateOwnerMemberRequest(
	String name,
	String email,
	String phoneNumber,
	MemberRole role,
	String businessRegistrationNumber,
	String storeName,
	String businessType,
	LocationRequest storeLocation
) {
}
