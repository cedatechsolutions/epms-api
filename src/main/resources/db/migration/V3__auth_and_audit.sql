-- V3: Authentication hardening + audit trail (spec Module 1 §4, data model §3.1).
--
-- Portable across H2 (PostgreSQL mode, local/dev) and PostgreSQL (prod):
--   * "timestamp with time zone" maps to timestamptz on both.
--   * JSON payloads are stored as TEXT and (de)serialized in the service layer
--     (H2 in PostgreSQL mode does not support jsonb).
-- UUID varchar primary keys stay consistent with the existing schema (plan §0).

-- 1. Account-security + first-login columns on users.
ALTER TABLE users ADD COLUMN must_change_password boolean DEFAULT false NOT NULL;
ALTER TABLE users ADD COLUMN failed_attempts integer DEFAULT 0 NOT NULL;
ALTER TABLE users ADD COLUMN locked_until timestamp(6) with time zone;
ALTER TABLE users ADD COLUMN deleted_at timestamp(6) with time zone;

-- 2. Single-use password reset tokens (spec: valid 60 minutes, hashed at rest).
CREATE TABLE password_resets (
    id varchar(255) NOT NULL,
    user_id varchar(255) NOT NULL,
    token_hash varchar(255) NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    used_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT password_resets_pkey PRIMARY KEY (id),
    CONSTRAINT uk_password_resets_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_password_resets_user FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX idx_password_resets_user ON password_resets (user_id);

-- 3. Rotating refresh tokens (spec: 7-day, rotating; logout/rotation revokes).
CREATE TABLE refresh_tokens (
    id varchar(255) NOT NULL,
    user_id varchar(255) NOT NULL,
    token_hash varchar(255) NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    revoked_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id),
    CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);

-- 4. Activity log — one row per login and per create/update/delete/approve/reject
--    on primary entities (written after commit, never blocking).
CREATE TABLE activity_logs (
    id varchar(255) NOT NULL,
    user_id varchar(255),
    action varchar(255) NOT NULL,
    entity_type varchar(255),
    entity_id varchar(255),
    metadata text,
    ip_address varchar(255),
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT activity_logs_pkey PRIMARY KEY (id),
    CONSTRAINT fk_activity_logs_user FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX idx_activity_logs_user ON activity_logs (user_id);
CREATE INDEX idx_activity_logs_action ON activity_logs (action);
CREATE INDEX idx_activity_logs_created_at ON activity_logs (created_at);
CREATE INDEX idx_activity_logs_entity ON activity_logs (entity_type, entity_id);
