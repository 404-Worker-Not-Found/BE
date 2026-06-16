CREATE TABLE locations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    address VARCHAR(255) NOT NULL,
    detail_address VARCHAR(255) NULL,
    latitude DECIMAL(10, 7) NOT NULL,
    longitude DECIMAL(10, 7) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE members (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    name VARCHAR(50) NOT NULL,
    email VARCHAR(255) NOT NULL,
    phone_number VARCHAR(20) NOT NULL,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    joined_at DATETIME(6) NOT NULL,
    withdrawn_at DATETIME(6) NULL,
    blocked_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_members_email UNIQUE (email),
    CONSTRAINT uk_members_phone_number UNIQUE (phone_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE owner_profiles (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    member_id BIGINT NOT NULL,
    business_registration_number VARCHAR(20) NOT NULL,
    business_type VARCHAR(100) NOT NULL,
    business_verification_status VARCHAR(20) NOT NULL,
    store_location_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_owner_profiles_member_id UNIQUE (member_id),
    CONSTRAINT fk_owner_profiles_member
        FOREIGN KEY (member_id) REFERENCES members (id),
    CONSTRAINT fk_owner_profiles_store_location
        FOREIGN KEY (store_location_id) REFERENCES locations (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE worker_profiles (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    member_id BIGINT NOT NULL,
    desired_hourly_wage INT NOT NULL,
    activity_radius_km INT NOT NULL,
    immediately_available BIT NOT NULL,
    base_location_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_worker_profiles_member_id UNIQUE (member_id),
    CONSTRAINT fk_worker_profiles_member
        FOREIGN KEY (member_id) REFERENCES members (id),
    CONSTRAINT fk_worker_profiles_base_location
        FOREIGN KEY (base_location_id) REFERENCES locations (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE worker_preferred_business_types (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    worker_profile_id BIGINT NOT NULL,
    business_type VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_worker_preferred_business_types_profile_type UNIQUE (worker_profile_id, business_type),
    CONSTRAINT fk_worker_preferred_business_types_profile
        FOREIGN KEY (worker_profile_id) REFERENCES worker_profiles (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE worker_available_times (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    worker_profile_id BIGINT NOT NULL,
    day_of_week VARCHAR(20) NOT NULL,
    start_time TIME(6) NOT NULL,
    end_time TIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_worker_available_times_profile_time UNIQUE (
        worker_profile_id,
        day_of_week,
        start_time,
        end_time
    ),
    CONSTRAINT fk_worker_available_times_profile
        FOREIGN KEY (worker_profile_id) REFERENCES worker_profiles (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
