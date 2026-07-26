CREATE TABLE personal_profile (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    user_id             BIGINT          NOT NULL,
    full_name           VARCHAR(128)    NULL,
    email               VARCHAR(128)    NULL,
    phone               VARCHAR(64)     NULL,
    location            VARCHAR(255)    NULL,
    website             VARCHAR(512)    NULL,
    profile_summary     TEXT            NULL,
    created_at          DATETIME(3)     NOT NULL,
    updated_at          DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_personal_profile_user (user_id),
    CONSTRAINT fk_personal_profile_user FOREIGN KEY (user_id) REFERENCES user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
