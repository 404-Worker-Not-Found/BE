ALTER TABLE matchings DROP CHECK ck_matchings_status,
 ADD CONSTRAINT ck_matchings_status CHECK (status IN ('PENDING','CONFIRMED','DECLINED','EXPIRED','CANCELED','COMPLETED'));
ALTER TABLE matching_status_histories
 DROP CHECK ck_matching_status_histories_from_status,
 DROP CHECK ck_matching_status_histories_to_status,
 ADD CONSTRAINT ck_matching_status_histories_from_status CHECK (from_status IS NULL OR from_status IN ('PENDING','CONFIRMED','DECLINED','EXPIRED','CANCELED','COMPLETED')),
 ADD CONSTRAINT ck_matching_status_histories_to_status CHECK (to_status IN ('PENDING','CONFIRMED','DECLINED','EXPIRED','CANCELED','COMPLETED'));

CREATE TABLE matching_work_terminations (
 work_id BIGINT NOT NULL PRIMARY KEY,
 matching_id BIGINT NOT NULL UNIQUE,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 event_type VARCHAR(40) NOT NULL,
 occurred_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_work_termination_matching FOREIGN KEY (matching_id) REFERENCES matchings(id)
);
CREATE TABLE matching_work_event_receipts (
 event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 work_id BIGINT NOT NULL,
 matching_id BIGINT NOT NULL,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 result VARCHAR(40) NOT NULL,
 received_at DATETIME(6) NOT NULL
);
