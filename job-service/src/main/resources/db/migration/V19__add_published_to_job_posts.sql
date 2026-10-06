-- 검증된 예치 확인으로 공개(PAYMENT_PENDING -> OPEN)된 적이 있는지. 상세 조회 권한은 현재 상태가 아니라 이 값으로 정한다.
-- 결제 대기 공고가 공개 전에 마감되면 CLOSED가 되므로 상태만으로는 공개된 적 없는 공고를 구분할 수 없기 때문이다.
ALTER TABLE job_posts
    ADD COLUMN published BOOLEAN NOT NULL DEFAULT FALSE;

-- 결제 대기가 아닌 기존 공고(V10 이전 공고 포함)는 공개된 적이 있는 공고다. 단, 공개 전 마감(PAYMENT_PENDING -> CLOSED)
-- 이력이 있는 공고는 공개된 적이 없으므로 제외한다.
UPDATE job_posts j
SET j.published = TRUE
WHERE j.status <> 'PAYMENT_PENDING'
  AND NOT EXISTS (
      SELECT 1 FROM job_status_histories h
      WHERE h.job_post_id = j.id
        AND h.from_status = 'PAYMENT_PENDING'
        AND h.to_status = 'CLOSED'
  );
