-- 공개에 쓰이지 못했거나 이전 주문에 남은 예치 확인의 환불 검토 대상. 주문당 한 건이며, 처음 검토 대상이 된 수신 기록에 연결한다.
-- 실제 환불·정산은 하지 않는다.
CREATE TABLE job_payment_refund_reviews (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    job_post_id BIGINT      NOT NULL,
    order_id    VARCHAR(64) NOT NULL,
    receipt_id  BIGINT      NOT NULL,
    reason      VARCHAR(40) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_job_payment_refund_reviews_order_id UNIQUE (order_id),
    CONSTRAINT uk_job_payment_refund_reviews_receipt UNIQUE (receipt_id),
    CONSTRAINT fk_job_payment_refund_reviews_job_post
        FOREIGN KEY (job_post_id) REFERENCES job_posts (id),
    CONSTRAINT fk_job_payment_refund_reviews_receipt
        FOREIGN KEY (receipt_id) REFERENCES job_funding_status_receipts (id),
    INDEX idx_job_payment_refund_reviews_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 이미 환불 검토로 표시된 수신 기록을 주문별 첫 기록 기준으로 옮긴다. 수신 기록의 표시 컬럼은 그대로 둔다.
INSERT INTO job_payment_refund_reviews (created_at, updated_at, job_post_id, order_id, receipt_id, reason)
SELECT r.received_at, r.received_at, r.job_post_id, r.order_id, r.id, COALESCE(r.skip_reason, r.result)
FROM job_funding_status_receipts r
JOIN (
    SELECT order_id, MIN(id) AS first_id
    FROM job_funding_status_receipts
    WHERE refund_review_required = TRUE
    GROUP BY order_id
) first_review ON first_review.first_id = r.id;
