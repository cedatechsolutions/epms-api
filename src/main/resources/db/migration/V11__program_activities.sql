-- V11: Activities, attendance and evaluations (spec Module 5b, data model §3.5).
--
-- This is where an approved proposal becomes a delivered program. The three tables form a chain:
--
--   programs --1:N--> program_activities --1:N--> attendance_records   (who actually attended)
--                                        --1:N--> evaluations          (pre/post summary per session)
--
-- Two program-status transitions are driven from here and nowhere else (via ProgramStateMachine,
-- which remains the only writer of programs.status):
--   * approved --> ongoing    when the first activity is recorded as done
--   * ongoing  --> completed  when every activity is done/cancelled AND >=1 post evaluation exists
--
-- GAD is first-class (cross-cutting rule 1): attendance_records.sex is NOT NULL, and evaluations
-- carry female/male counts alongside the total so every count surface can show Total/F/M.
--
-- Portable across H2 (PostgreSQL mode) and PostgreSQL, matching the V4/V5/V8/V9 conventions:
--   * varchar(255) UUID primary keys, timestamp(6) with time zone.
--   * Named FK/CHECK constraints; soft delete via deleted_at on the primary entity only.

-- 0. Who is assigned to a program besides its faculty lead.
--    Phase 4 deferred this deliberately ("own" was created_by OR faculty_lead_id). Without it two
--    spec requirements have no way to be true: Module 5 §1's "faculty sees own AND ASSIGNED
--    programs", and §2.2's student volunteers "viewing the programs they help run" — a student can
--    neither create nor lead a program, so before this table they could see none at all.
--
--    Membership grants VISIBILITY ONLY. Authorship (editing the proposal) and delivery (recording
--    attendance) still require being the creator/lead or a coordinator — see ProgramAccessPolicy.
--    A volunteer helping run a session must be able to look it up; that is not the same as being
--    able to rewrite the beneficiary record.
CREATE TABLE program_members (
    id varchar(255) NOT NULL,
    program_id varchar(255) NOT NULL,
    user_id varchar(255) NOT NULL,
    role_in_program varchar(64) NOT NULL DEFAULT 'volunteer',
    assigned_by varchar(255),
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT program_members_pkey PRIMARY KEY (id),
    CONSTRAINT uq_program_members_program_user UNIQUE (program_id, user_id),
    CONSTRAINT chk_program_members_role CHECK (role_in_program IN ('volunteer', 'co_faculty')),
    CONSTRAINT fk_pm_program FOREIGN KEY (program_id) REFERENCES programs (id),
    CONSTRAINT fk_pm_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_pm_assigned_by FOREIGN KEY (assigned_by) REFERENCES users (id)
);
-- user_id is the hot column: every list query for a restricted role asks "which programs am I on?".
CREATE INDEX idx_program_members_user ON program_members (user_id);
CREATE INDEX idx_program_members_program ON program_members (program_id);

-- 1. A session of a program: one date, one venue. A program may have many.
--    Soft-deleted (cross-cutting rule 3) because attendance_records FK this table — a hard delete
--    would either orphan beneficiary records or cascade them away, and attendance is the evidence
--    the accomplishment report is built from.
CREATE TABLE program_activities (
    id varchar(255) NOT NULL,
    program_id varchar(255) NOT NULL,
    title varchar(255) NOT NULL,
    activity_date date NOT NULL,
    start_time time,
    end_time time,
    venue varchar(255),
    status varchar(32) NOT NULL DEFAULT 'scheduled',
    notes text,
    created_by varchar(255),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    CONSTRAINT program_activities_pkey PRIMARY KEY (id),
    CONSTRAINT chk_program_activities_status CHECK (status IN ('scheduled', 'done', 'cancelled')),
    CONSTRAINT fk_pact_program FOREIGN KEY (program_id) REFERENCES programs (id),
    CONSTRAINT fk_pact_created_by FOREIGN KEY (created_by) REFERENCES users (id)
);
-- program_id drives the activities tab and the completion check; status drives both the completion
-- check and the Phase 6 "communities served" aggregate (distinct communities with >=1 done activity).
CREATE INDEX idx_program_activities_program ON program_activities (program_id);
CREATE INDEX idx_program_activities_status ON program_activities (status);
CREATE INDEX idx_program_activities_date ON program_activities (activity_date);
CREATE INDEX idx_program_activities_deleted_at ON program_activities (deleted_at);

-- 2. One row per person present at one activity.
--    sex is NOT NULL and CHECK-constrained: the GAD split is the point of this table, and a nullable
--    sex would silently produce a third bucket that no report knows how to render.
--    community_id is denormalized from the parent program so beneficiary exports can group by
--    community without walking two joins, and so a program that later changes community does not
--    retroactively rewrite who was served where.
CREATE TABLE attendance_records (
    id varchar(255) NOT NULL,
    program_activity_id varchar(255) NOT NULL,
    attendee_name varchar(255) NOT NULL,
    sex varchar(16) NOT NULL,
    age integer,
    sector_id varchar(255),
    community_id varchar(255),
    created_by varchar(255),
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT attendance_records_pkey PRIMARY KEY (id),
    CONSTRAINT chk_attendance_sex CHECK (sex IN ('female', 'male')),
    CONSTRAINT chk_attendance_age CHECK (age IS NULL OR (age >= 0 AND age <= 130)),
    CONSTRAINT fk_att_activity FOREIGN KEY (program_activity_id) REFERENCES program_activities (id),
    CONSTRAINT fk_att_sector FOREIGN KEY (sector_id) REFERENCES sectors (id),
    CONSTRAINT fk_att_community FOREIGN KEY (community_id) REFERENCES communities (id),
    CONSTRAINT fk_att_created_by FOREIGN KEY (created_by) REFERENCES users (id)
);
CREATE INDEX idx_attendance_activity ON attendance_records (program_activity_id);
CREATE INDEX idx_attendance_sector ON attendance_records (sector_id);
CREATE INDEX idx_attendance_community ON attendance_records (community_id);
-- The F/M rollups group by (activity, sex); a composite index answers them from the index alone.
CREATE INDEX idx_attendance_activity_sex ON attendance_records (program_activity_id, sex);

-- 3. Pre/post evaluation summary for one activity. This is an ENCODED SUMMARY, not per-respondent
--    rows — the spec asks for counts and an average, and the raw instruments stay on paper (the
--    optional scanned file is attached via file_path).
--    female_count + male_count are stored rather than derived: evaluation respondents are not the
--    same population as attendance_records (someone present may not have answered), so they cannot
--    be recomputed from attendance.
CREATE TABLE evaluations (
    id varchar(255) NOT NULL,
    program_activity_id varchar(255) NOT NULL,
    eval_type varchar(16) NOT NULL,
    respondent_count integer NOT NULL,
    female_count integer NOT NULL DEFAULT 0,
    male_count integer NOT NULL DEFAULT 0,
    avg_rating numeric(4, 2),
    notes text,
    file_path varchar(512),
    original_filename varchar(255),
    mime_type varchar(128),
    size_bytes bigint,
    encoded_by varchar(255),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone,
    CONSTRAINT evaluations_pkey PRIMARY KEY (id),
    CONSTRAINT chk_evaluations_type CHECK (eval_type IN ('pre', 'post')),
    CONSTRAINT chk_evaluations_counts CHECK (
        respondent_count >= 0 AND female_count >= 0 AND male_count >= 0),
    -- The split may not exceed the total. It is allowed to be SMALLER: a respondent who declined to
    -- state their sex is counted in the total and in neither bucket, which the UI reports in words
    -- rather than inventing a third category for.
    CONSTRAINT chk_evaluations_split CHECK (female_count + male_count <= respondent_count),
    CONSTRAINT chk_evaluations_rating CHECK (avg_rating IS NULL OR (avg_rating >= 1 AND avg_rating <= 5)),
    CONSTRAINT fk_eval_activity FOREIGN KEY (program_activity_id) REFERENCES program_activities (id),
    CONSTRAINT fk_eval_encoded_by FOREIGN KEY (encoded_by) REFERENCES users (id)
);
CREATE INDEX idx_evaluations_activity ON evaluations (program_activity_id);
-- The completion rule asks "does this program have >=1 post evaluation?" on every activity change.
CREATE INDEX idx_evaluations_activity_type ON evaluations (program_activity_id, eval_type);
