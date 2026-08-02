-- V5: Needs Assessment survey builder (spec Module 3, data model §3.3 — Pass A).
--
-- Portable across H2 (PostgreSQL mode, local/dev) and PostgreSQL (prod):
--   * "timestamp with time zone" maps to timestamptz on both.
--   * options JSON is stored as TEXT and (de)serialized in the service layer (matches activity_logs.metadata).
-- UUID varchar primary keys stay consistent with the existing schema (plan §0).

-- 1. Surveys — one per community, lifecycle draft -> deployed -> closed (spec §3.3).
CREATE TABLE surveys (
    id varchar(255) NOT NULL,
    community_id varchar(255) NOT NULL,
    title varchar(255) NOT NULL,
    description text,
    status varchar(16) NOT NULL DEFAULT 'draft',
    access_token varchar(64),
    opens_at timestamp(6) with time zone,
    closes_at timestamp(6) with time zone,
    target_responses integer,
    created_by varchar(255),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    CONSTRAINT surveys_pkey PRIMARY KEY (id),
    CONSTRAINT uk_surveys_access_token UNIQUE (access_token),
    CONSTRAINT chk_surveys_status CHECK (status IN ('draft', 'deployed', 'closed')),
    CONSTRAINT fk_surveys_community FOREIGN KEY (community_id) REFERENCES communities (id),
    CONSTRAINT fk_surveys_created_by FOREIGN KEY (created_by) REFERENCES users (id)
);
CREATE INDEX idx_surveys_community ON surveys (community_id);
CREATE INDEX idx_surveys_status ON surveys (status);
CREATE INDEX idx_surveys_deleted_at ON surveys (deleted_at);

-- 2. Need categories — seeded lookup, admin-manageable via is_active (spec §3.3 line 143).
CREATE TABLE need_categories (
    id varchar(255) NOT NULL,
    name varchar(255) NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    CONSTRAINT need_categories_pkey PRIMARY KEY (id),
    CONSTRAINT uk_need_categories_name UNIQUE (name)
);

-- Deterministic ids keep FK references and scoring fixtures stable across environments.
INSERT INTO need_categories (id, name, is_active) VALUES
    ('30000000-0000-0000-0000-000000000001', 'Health', true),
    ('30000000-0000-0000-0000-000000000002', 'Livelihood', true),
    ('30000000-0000-0000-0000-000000000003', 'Education', true),
    ('30000000-0000-0000-0000-000000000004', 'Environment', true),
    ('30000000-0000-0000-0000-000000000005', 'Safety', true),
    ('30000000-0000-0000-0000-000000000006', 'Governance', true),
    ('30000000-0000-0000-0000-000000000007', 'General', true);

-- 3. Survey questions — ordered content questions (the GAD demographic block is fixed in the
--    response schema, not stored here). options holds choice-type labels as JSON text.
CREATE TABLE survey_questions (
    id varchar(255) NOT NULL,
    survey_id varchar(255) NOT NULL,
    order_index integer NOT NULL DEFAULT 0,
    question_text text NOT NULL,
    question_type varchar(32) NOT NULL,
    options text,
    weight numeric(4, 2) NOT NULL DEFAULT 1.0,
    need_category_id varchar(255),
    is_required boolean NOT NULL DEFAULT true,
    CONSTRAINT survey_questions_pkey PRIMARY KEY (id),
    CONSTRAINT chk_survey_questions_type CHECK (
        question_type IN ('rating', 'multiple_choice', 'checkbox', 'open_text')),
    CONSTRAINT fk_survey_questions_survey FOREIGN KEY (survey_id) REFERENCES surveys (id),
    CONSTRAINT fk_survey_questions_category FOREIGN KEY (need_category_id) REFERENCES need_categories (id)
);
CREATE INDEX idx_survey_questions_survey ON survey_questions (survey_id);
