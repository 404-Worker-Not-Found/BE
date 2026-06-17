INSERT INTO industry_categories (created_at, updated_at, name, parent_id, is_active) VALUES
-- 대분류
(NOW(), NOW(), '외식/음료',     NULL, true),
(NOW(), NOW(), '유통/판매',     NULL, true),
(NOW(), NOW(), '물류/배송',     NULL, true),
(NOW(), NOW(), '서비스',        NULL, true),
(NOW(), NOW(), '사무/행정',     NULL, true),
(NOW(), NOW(), '교육',          NULL, true),
(NOW(), NOW(), '기타',          NULL, true),

-- 외식/음료 하위
(NOW(), NOW(), '카페/음료',     1, true),
(NOW(), NOW(), '패스트푸드',    1, true),
(NOW(), NOW(), '일반음식점',    1, true),

-- 유통/판매 하위
(NOW(), NOW(), '편의점',        2, true),
(NOW(), NOW(), '마트/슈퍼',     2, true),
(NOW(), NOW(), '의류/잡화',     2, true),

-- 물류/배송 하위
(NOW(), NOW(), '택배/배송',     3, true),
(NOW(), NOW(), '창고/물류',     3, true),

-- 서비스 하위
(NOW(), NOW(), '미용/뷰티',     4, true),
(NOW(), NOW(), '청소/시설',     4, true),
(NOW(), NOW(), '돌봄/케어',     4, true);
