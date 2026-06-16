CREATE TABLE auth_accounts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    member_id BIGINT NOT NULL,
    email VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    signup_type VARCHAR(20) NOT NULL,
    last_login_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_auth_accounts_email UNIQUE (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE local_credentials (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    auth_account_id BIGINT NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    password_changed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_local_credentials_auth_account_id UNIQUE (auth_account_id),
    CONSTRAINT fk_local_credentials_auth_account
        FOREIGN KEY (auth_account_id) REFERENCES auth_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE oauth_connections (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    auth_account_id BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    provider_email VARCHAR(255) NULL,
    connected_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_oauth_connections_provider_user UNIQUE (provider, provider_user_id),
    CONSTRAINT fk_oauth_connections_auth_account
        FOREIGN KEY (auth_account_id) REFERENCES auth_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE refresh_tokens (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    auth_account_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    device_id VARCHAR(100) NOT NULL,
    token_hash VARCHAR(255) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    replaced_by_token_id BIGINT NULL,
    last_used_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_auth_account
        FOREIGN KEY (auth_account_id) REFERENCES auth_accounts (id),
    INDEX idx_refresh_tokens_account_device (auth_account_id, device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
