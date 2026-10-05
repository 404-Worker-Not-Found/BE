-- 공고에 연결된 최신 주문의 예치가 취소·검토 필요로 확인되어 신규 지원 승인과 신규 자리 예약을 막는 상태.
-- 공고 상태(status)와 별개로 저장한다. 차단을 상태 되돌리기로 표현하면 마감 공고가 나중에 다시 공개될 수 있기 때문이다.
ALTER TABLE job_posts
    ADD COLUMN funding_blocked BOOLEAN NOT NULL DEFAULT FALSE;

-- 주문별로 마지막에 적용한 예치 상태 revision. 이보다 낮거나 같은 revision은 예치 상태와 공고 상태를 바꾸지 못한다.
CREATE TABLE job_payment_fundings (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    job_post_id      BIGINT      NOT NULL,
    order_id         VARCHAR(64) NOT NULL,
    funding_revision BIGINT      NOT NULL,
    funded           BOOLEAN     NOT NULL,
    applied_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_job_payment_fundings_order_id UNIQUE (order_id),
    CONSTRAINT ck_job_payment_fundings_revision CHECK (funding_revision >= 1),
    CONSTRAINT fk_job_payment_fundings_job_post
        FOREIGN KEY (job_post_id) REFERENCES job_posts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 검증을 통과한 예치 상태 알림의 영구 수신 기록. 같은 키 재요청은 이 행으로 원래 응답을 돌려준다.
-- 연결 대기·주문 불일치·멱등 충돌처럼 거절한 알림은 저장하지 않아 정상 알림의 키와 revision을 선점하지 않는다.
CREATE TABLE job_funding_status_receipts (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    created_at             DATETIME(6)  NOT NULL,
    updated_at             DATETIME(6)  NOT NULL,
    idempotency_key        VARCHAR(100) NOT NULL,
    job_post_id            BIGINT       NOT NULL,
    order_id               VARCHAR(64)  NOT NULL,
    job_version            BIGINT       NOT NULL,
    owner_member_id        BIGINT       NOT NULL,
    amount                 BIGINT       NOT NULL,
    currency               VARCHAR(3)   NOT NULL,
    funding_revision       BIGINT       NOT NULL,
    funded                 BOOLEAN      NOT NULL,
    result                 VARCHAR(30)  NOT NULL,
    skip_reason            VARCHAR(40)  NULL,
    job_status             VARCHAR(20)  NOT NULL,
    funding_blocked        BOOLEAN      NOT NULL,
    refund_review_required BOOLEAN      NOT NULL,
    received_at            DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_job_funding_status_receipts_idempotency_key UNIQUE (idempotency_key),
    -- 같은 주문·revision은 키가 달라도 한 번만 처리한다. 내용이 다른 재전송을 충돌로 판정하는 기준 행이다.
    CONSTRAINT uk_job_funding_status_receipts_order_revision UNIQUE (order_id, funding_revision),
    CONSTRAINT fk_job_funding_status_receipts_job_post
        FOREIGN KEY (job_post_id) REFERENCES job_posts (id),
    -- 공개하지 못했거나 이전 주문에 남은 예치처럼 환불 검토가 필요한 수신 기록을 찾는다.
    INDEX idx_job_funding_status_receipts_refund_review (refund_review_required, received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
