package com.workernotfound.member.domain.location.service;

import com.workernotfound.member.domain.location.entity.Location;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

@Service
public class LocationCommandService {

	public Location createLocation(
		String address,
		String detailAddress,
		BigDecimal latitude,
		BigDecimal longitude
	) {
		return Location.builder()
			.address(address)
			.detailAddress(detailAddress)
			.latitude(latitude)
			.longitude(longitude)
			.build();
	}
}
