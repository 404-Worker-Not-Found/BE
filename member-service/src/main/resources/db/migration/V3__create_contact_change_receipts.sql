CREATE TABLE contact_change_receipts (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 member_id BIGINT NOT NULL,
 fingerprint VARCHAR(64) NOT NULL,
 accepted BIT NOT NULL,
 CONSTRAINT fk_contact_change_receipts_member FOREIGN KEY (member_id) REFERENCES members(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
