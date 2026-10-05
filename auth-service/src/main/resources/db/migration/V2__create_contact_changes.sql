CREATE TABLE contact_changes (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 account_id BIGINT NOT NULL,
 member_id BIGINT NOT NULL,
 channel VARCHAR(10) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL,
 target VARCHAR(255) NULL,
 previous_email VARCHAR(255) NULL,
 status VARCHAR(20) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 next_attempt_at DATETIME(6) NOT NULL,
 attempts INT NOT NULL,
 INDEX ix_contact_changes_pending (status, next_attempt_at),
 CONSTRAINT fk_contact_changes_account FOREIGN KEY (account_id) REFERENCES auth_accounts(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
