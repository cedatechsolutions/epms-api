-- V9: Programs & Approval Workflow (spec Module 5a, data model §3.5).
--
-- The four-stage CvSU signatory chain is the heart of the system. Statuses are the queue a proposal
-- sits in; each stage's action moves it to the next one (see ProgramStateMachine, which is the ONLY
-- place allowed to change programs.status):
--
--   draft/returned --submit--> submitted --note--> coordinator_review
--     --recommend--> recommending_approval --approve--> approved --> ongoing --> completed
--
-- A return from any stage sets 'returned'; resubmitting re-enters at stage 2 ('submitted').
-- 'ongoing' and 'completed' are declared here but only driven in Phase 5 (activities/attendance).
--
-- Portable across H2 (PostgreSQL mode) and PostgreSQL, matching V4/V5/V8 conventions:
--   * varchar(255) UUID primary keys, timestamp(6) with time zone.
--   * Named FK/CHECK constraints; soft delete via deleted_at on the primary entity only.

-- 1. The proposal / program master record.
--    recommendation_id and survey_id are nullable provenance links: a program may be born from the
--    recommendation engine (Module 4) or drafted by hand, and the assessment that justifies it is an
--    EXTN requirement but not enforceable at insert time (a draft is saved with title alone).
CREATE TABLE programs (
    id varchar(255) NOT NULL,
    title varchar(255) NOT NULL,
    community_id varchar(255),
    program_type_id varchar(255),
    recommendation_id varchar(255),
    survey_id varchar(255),
    objectives text,
    target_beneficiaries integer,
    proposed_date date,
    end_date date,
    venue varchar(255),
    budget_requested numeric(12, 2),
    budget_approved numeric(12, 2),
    faculty_lead_id varchar(255),
    status varchar(32) NOT NULL DEFAULT 'draft',
    created_by varchar(255),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    CONSTRAINT programs_pkey PRIMARY KEY (id),
    CONSTRAINT chk_programs_status CHECK (status IN (
        'draft', 'submitted', 'coordinator_review', 'recommending_approval',
        'approved', 'returned', 'ongoing', 'completed', 'cancelled')),
    CONSTRAINT fk_programs_community FOREIGN KEY (community_id) REFERENCES communities (id),
    CONSTRAINT fk_programs_program_type FOREIGN KEY (program_type_id) REFERENCES program_types (id),
    CONSTRAINT fk_programs_recommendation FOREIGN KEY (recommendation_id) REFERENCES recommendations (id),
    CONSTRAINT fk_programs_survey FOREIGN KEY (survey_id) REFERENCES surveys (id),
    CONSTRAINT fk_programs_faculty_lead FOREIGN KEY (faculty_lead_id) REFERENCES users (id),
    CONSTRAINT fk_programs_created_by FOREIGN KEY (created_by) REFERENCES users (id)
);
-- status/community/type back the list tabs and the Phase 6 dashboard aggregates; faculty_lead_id and
-- created_by back the "own + assigned" visibility filter applied to every faculty list query.
CREATE INDEX idx_programs_status ON programs (status);
CREATE INDEX idx_programs_community ON programs (community_id);
CREATE INDEX idx_programs_program_type ON programs (program_type_id);
CREATE INDEX idx_programs_faculty_lead ON programs (faculty_lead_id);
CREATE INDEX idx_programs_created_by ON programs (created_by);
CREATE INDEX idx_programs_deleted_at ON programs (deleted_at);

-- 2. Target sectors for the program (same tagging model as community_sector in V4).
CREATE TABLE program_sector (
    program_id varchar(255) NOT NULL,
    sector_id varchar(255) NOT NULL,
    CONSTRAINT program_sector_pkey PRIMARY KEY (program_id, sector_id),
    CONSTRAINT fk_ps_program FOREIGN KEY (program_id) REFERENCES programs (id),
    CONSTRAINT fk_ps_sector FOREIGN KEY (sector_id) REFERENCES sectors (id)
);

-- 3. The audit trail of the approval chain: one immutable row per stage decision.
--    Rows are never updated or deleted — a resubmission appends, it does not rewrite history, so a
--    proposal that bounced twice keeps both returns. stage_role records WHICH signatory acted, which
--    matters because the spec permission matrix binds each stage to exactly one role.
CREATE TABLE program_approvals (
    id varchar(255) NOT NULL,
    program_id varchar(255) NOT NULL,
    stage integer NOT NULL,
    stage_role varchar(64) NOT NULL,
    action varchar(32) NOT NULL,
    acted_by varchar(255),
    comment text,
    acted_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT program_approvals_pkey PRIMARY KEY (id),
    CONSTRAINT chk_program_approvals_stage CHECK (stage BETWEEN 1 AND 4),
    CONSTRAINT chk_program_approvals_stage_role CHECK (stage_role IN (
        'faculty', 'extension_coordinator', 'campus_extension_coordinator', 'campus_admin')),
    CONSTRAINT chk_program_approvals_action CHECK (action IN (
        'submitted', 'noted', 'recommended', 'approved', 'returned')),
    CONSTRAINT fk_pa_program FOREIGN KEY (program_id) REFERENCES programs (id),
    CONSTRAINT fk_pa_acted_by FOREIGN KEY (acted_by) REFERENCES users (id)
);
CREATE INDEX idx_program_approvals_program ON program_approvals (program_id);

-- 4. Proposal attachments. Same storage model as community_documents (V4): bytes live outside the
--    webroot via StorageService, only metadata is persisted here.
CREATE TABLE program_documents (
    id varchar(255) NOT NULL,
    program_id varchar(255) NOT NULL,
    file_path varchar(512) NOT NULL,
    original_filename varchar(255) NOT NULL,
    mime_type varchar(128),
    size_bytes bigint,
    doc_type varchar(64) NOT NULL DEFAULT 'other',
    uploaded_by varchar(255),
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT program_documents_pkey PRIMARY KEY (id),
    CONSTRAINT chk_program_documents_doc_type CHECK (doc_type IN (
        'letter_request', 'moa', 'budget_breakdown', 'needs_assessment_report', 'photo', 'other')),
    CONSTRAINT fk_pd_program FOREIGN KEY (program_id) REFERENCES programs (id),
    CONSTRAINT fk_pd_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users (id)
);
CREATE INDEX idx_program_documents_program ON program_documents (program_id);
