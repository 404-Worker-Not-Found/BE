package com.workernotfound.member.domain.location.entity;

import com.workernotfound.member.domain.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "locations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Location extends BaseEntity {

	@Column(nullable = false, length = 255)
	private String address;

	@Column(name = "detail_address", length = 255)
	private String detailAddress;

	@Column(nullable = false, precision = 10, scale = 7)
	private BigDecimal latitude;

	@Column(nullable = false, precision = 10, scale = 7)
	private BigDecimal longitude;

	@Builder
	private Location(String address, String detailAddress, BigDecimal latitude, BigDecimal longitude) {
		this.address = address;
		this.detailAddress = detailAddress;
		this.latitude = latitude;
		this.longitude = longitude;
	}
}
