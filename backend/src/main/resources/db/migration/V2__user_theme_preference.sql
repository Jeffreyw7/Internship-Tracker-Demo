ALTER TABLE campus_users ADD COLUMN theme_preference VARCHAR(6) NOT NULL DEFAULT 'system';
ALTER TABLE campus_users ADD CONSTRAINT chk_user_theme_preference
    CHECK (theme_preference IN ('system', 'light', 'dark'));
