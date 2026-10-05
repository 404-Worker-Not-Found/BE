CREATE TABLE account_gates (
 member_id BIGINT NOT NULL PRIMARY KEY,
 state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 command_id VARCHAR(36) NULL,
 CONSTRAINT ck_account_gate_state CHECK (state IN ('ACTIVE','PREPARED','WITHDRAWN'))
);
CREATE TABLE account_withdrawal_receipts (
 member_id BIGINT NOT NULL,
 command_id VARCHAR(36) NOT NULL,
 state VARCHAR(20) NOT NULL,
 PRIMARY KEY (member_id, command_id),
 CONSTRAINT fk_withdrawal_receipt_gate FOREIGN KEY (member_id) REFERENCES account_gates(member_id)
);
ALTER TABLE members MODIFY email VARCHAR(255) NULL, MODIFY phone_number VARCHAR(20) NULL;
