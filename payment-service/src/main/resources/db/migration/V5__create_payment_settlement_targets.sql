-- 정산 대상: matching-service MatchConfirmed v1이 확정한 매칭의 근무와 결제 잠금.
-- WorkCompleted에는 결제 ID와 금액이 없으므로 이 행으로 정산할 잠금을 정한다. 매칭 slot의 현재 잠금을 추측하지 않아
-- 이전 Saga 시도의 해제 잠금이나 다른 잠금이 정산 대상이 되지 않는다.
-- 확정 사실은 바뀌지 않으므로 한 번 기록한 행은 갱신하지 않는다. 기존 매칭은 Stream 재생으로만 기록하고 소급 추정하지 않는다.
CREATE TABLE payment_settlement_targets (
 matching_id BIGINT NOT NULL PRIMARY KEY,
 work_id BIGINT NOT NULL,
 payment_lock_id BIGINT NOT NULL,
 job_post_id BIGINT NOT NULL,
 owner_member_id BIGINT NOT NULL,
 worker_member_id BIGINT NOT NULL,
 locked_amount DECIMAL(19,2) NOT NULL,
 currency VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 matching_revision BIGINT NOT NULL,
 source_event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 confirmed_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_settlement_target_work (work_id),
 UNIQUE KEY uk_settlement_target_payment_lock (payment_lock_id),
 CONSTRAINT fk_settlement_target_payment_lock FOREIGN KEY (payment_lock_id) REFERENCES payment_locks(id),
 CONSTRAINT ck_settlement_target_amount CHECK (locked_amount > 0)
);
