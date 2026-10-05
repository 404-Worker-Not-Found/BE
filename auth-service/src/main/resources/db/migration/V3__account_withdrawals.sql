ALTER TABLE auth_accounts MODIFY email VARCHAR(255) NULL;
CREATE TABLE account_withdrawals (
 command_id VARCHAR(36) PRIMARY KEY,
 account_id BIGINT NOT NULL,
 member_id BIGINT NOT NULL,
 state VARCHAR(20) NOT NULL,
 next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 attempts INT NOT NULL DEFAULT 0,
 lease_token VARCHAR(36) NULL,
 lease_until DATETIME(6) NULL,
 blocked_service VARCHAR(20) NULL,
 completed_at DATETIME(6) NULL,
 INDEX ix_account_withdrawal_due (state,next_attempt_at,lease_until),
 CONSTRAINT fk_account_withdrawal_account FOREIGN KEY (account_id) REFERENCES auth_accounts(id)
);
