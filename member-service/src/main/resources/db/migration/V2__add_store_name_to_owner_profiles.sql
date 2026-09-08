ALTER TABLE owner_profiles
    ADD COLUMN store_name VARCHAR(100) NOT NULL DEFAULT '미입력' AFTER business_registration_number;

ALTER TABLE owner_profiles
    ALTER COLUMN store_name DROP DEFAULT;
