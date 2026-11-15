-- member-service review-rating-no-show-v1(#164)부터 리뷰 없이 노쇼만 있는 회원도 신뢰 점수를 받는다(reviewCount 0).
ALTER TABLE member_trust_scores DROP CHECK ck_member_trust_scores_reviews;
ALTER TABLE member_trust_scores ADD CONSTRAINT ck_member_trust_scores_reviews CHECK (review_count >= 0);
