-- V6: Public survey responses (spec Module 3 §3, data model §3.3 — Pass B).
--
-- GAD is first-class: respondent_sex is REQUIRED on every response so all downstream counts
-- can be sex-disaggregated. Age group and sector are optional.
-- Portable across H2 (PostgreSQL mode) and PostgreSQL.

-- 1. One row per submitted public response.
CREATE TABLE survey_responses (
    id varchar(255) NOT NULL,
    survey_id varchar(255) NOT NULL,
    respondent_token varchar(64),
    respondent_sex varchar(32) NOT NULL,
    respondent_age_group varchar(32),
    respondent_sector_id varchar(255),
    submitted_at timestamp(6) with time zone NOT NULL,
    ip_address varchar(255),
    CONSTRAINT survey_responses_pkey PRIMARY KEY (id),
    CONSTRAINT chk_survey_responses_sex CHECK (
        respondent_sex IN ('female', 'male', 'prefer_not_to_say')),
    CONSTRAINT chk_survey_responses_age_group CHECK (
        respondent_age_group IS NULL
        OR respondent_age_group IN ('under_18', '18_30', '31_45', '46_59', '60_plus')),
    CONSTRAINT fk_survey_responses_survey FOREIGN KEY (survey_id) REFERENCES surveys (id),
    CONSTRAINT fk_survey_responses_sector FOREIGN KEY (respondent_sector_id) REFERENCES sectors (id)
);
CREATE INDEX idx_survey_responses_survey ON survey_responses (survey_id);
CREATE INDEX idx_survey_responses_sex ON survey_responses (survey_id, respondent_sex);
-- Best-effort per-device dedupe: at most one response per token per survey.
CREATE UNIQUE INDEX uk_survey_responses_token
    ON survey_responses (survey_id, respondent_token);

-- 2. One row per answered question. Rating answers are stored as a numeric string;
--    checkbox answers as a JSON array of option values.
CREATE TABLE survey_answers (
    id varchar(255) NOT NULL,
    survey_response_id varchar(255) NOT NULL,
    survey_question_id varchar(255) NOT NULL,
    answer_value text,
    CONSTRAINT survey_answers_pkey PRIMARY KEY (id),
    CONSTRAINT fk_survey_answers_response FOREIGN KEY (survey_response_id) REFERENCES survey_responses (id),
    CONSTRAINT fk_survey_answers_question FOREIGN KEY (survey_question_id) REFERENCES survey_questions (id)
);
CREATE INDEX idx_survey_answers_response ON survey_answers (survey_response_id);
CREATE INDEX idx_survey_answers_question ON survey_answers (survey_question_id);
