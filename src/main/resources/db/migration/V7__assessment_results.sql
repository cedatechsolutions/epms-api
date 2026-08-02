-- V7: Finalized assessment results + the generated-report archive (spec Module 3 §4/§5,
--     data model §3.3 assessment_results and §3.6 generated_reports — Pass C).
--
-- Results are COMPUTED on demand; this table is the immutable snapshot taken when a coordinator
-- clicks "Finalize results". Recommendations (Phase 3) and the QF-23 report read from it.
-- Portable across H2 (PostgreSQL mode) and PostgreSQL.

CREATE TABLE assessment_results (
    id varchar(255) NOT NULL,
    survey_id varchar(255) NOT NULL,
    need_category_id varchar(255) NOT NULL,
    avg_score numeric(4, 2) NOT NULL,
    response_count integer NOT NULL,
    female_count integer NOT NULL,
    male_count integer NOT NULL,
    priority varchar(16) NOT NULL,
    -- "rank" is a SQL keyword in some engines; use an unambiguous column name.
    rank_position integer NOT NULL,
    computed_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT assessment_results_pkey PRIMARY KEY (id),
    CONSTRAINT chk_assessment_results_priority CHECK (
        priority IN ('critical', 'high', 'moderate', 'low')),
    CONSTRAINT uk_assessment_results_survey_category UNIQUE (survey_id, need_category_id),
    CONSTRAINT fk_assessment_results_survey FOREIGN KEY (survey_id) REFERENCES surveys (id),
    CONSTRAINT fk_assessment_results_category FOREIGN KEY (need_category_id) REFERENCES need_categories (id)
);
CREATE INDEX idx_assessment_results_survey ON assessment_results (survey_id);

-- Archive of generated documents. Files are immutable: regenerating writes a NEW row
-- (spec Module 7 archive semantics), so history is never overwritten.
--
-- NOTE: spec §3.6 lists file_type as ('pdf'|'xlsx'|'zip') but Module 3 §5 requires the QF-23 report
-- to be an editable .docx. 'docx' is therefore included here — the spec's enum is incomplete.
CREATE TABLE generated_reports (
    id varchar(255) NOT NULL,
    report_type varchar(48) NOT NULL,
    scope text,
    file_path varchar(1024) NOT NULL,
    file_type varchar(16) NOT NULL,
    generated_by varchar(255),
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT generated_reports_pkey PRIMARY KEY (id),
    CONSTRAINT chk_generated_reports_type CHECK (
        report_type IN ('accomplishment', 'beneficiary_data', 'needs_assessment_qf23', 'certificates')),
    CONSTRAINT chk_generated_reports_file_type CHECK (
        file_type IN ('pdf', 'xlsx', 'docx', 'zip')),
    CONSTRAINT fk_generated_reports_generated_by FOREIGN KEY (generated_by) REFERENCES users (id)
);
CREATE INDEX idx_generated_reports_type ON generated_reports (report_type);
CREATE INDEX idx_generated_reports_created_at ON generated_reports (created_at);
