-- V12: Academic periods (spec Module 6 §1) — the semester selector that drives the M&E dashboard.
--
-- A lookup, not a workflow entity: rows are seeded here and read-only through the API. There is no
-- FK from programs to this table on purpose. A program is *mapped* to a period by date, not
-- *assigned* to one, so re-drawing a semester's boundaries (which the registrar does) reclassifies
-- history correctly instead of leaving thousands of stale foreign keys pointing at the old shape.
--
-- The mapping rule, applied identically by every dashboard aggregate and by the programs list's
-- period filter (MonitoringDashboardService / ProgramService):
--
--     a program belongs to a period when programs.proposed_date falls in [starts_on, ends_on]
--
-- One anchor, not two. The spec phrases it "by proposed/activity dates", and anchoring beneficiary
-- counts on activity_date while anchoring program counts on proposed_date would let a program that
-- runs past the semester break report its attendance in one period and itself in another — the KPI
-- and the list behind it would then disagree, breaking Module 6 AC 6 ("every count traceable to a
-- drill-down"). Attendance is therefore counted for the program that drew it, wherever the session
-- physically fell. Programs with no proposed_date (untouched drafts) belong to no period and appear
-- only under "All periods".

CREATE TABLE academic_periods (
    id varchar(255) NOT NULL,
    label varchar(128) NOT NULL,
    starts_on date NOT NULL,
    ends_on date NOT NULL,
    -- Seeded hint, NOT the source of truth for "which period are we in?".
    -- AcademicPeriodService resolves the current period from the date range first and consults this
    -- column only when today falls in a gap between semesters. A stored flag goes stale silently on
    -- the day a term ends; a date range never does.
    is_current boolean NOT NULL DEFAULT false,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT academic_periods_pkey PRIMARY KEY (id),
    CONSTRAINT uq_academic_periods_label UNIQUE (label),
    CONSTRAINT chk_academic_periods_range CHECK (ends_on >= starts_on)
);
-- Every dashboard query filters programs by a period's date window, and the selector lists them in
-- calendar order.
CREATE INDEX idx_academic_periods_starts_on ON academic_periods (starts_on);

-- Programs are filtered by proposed_date on every period-scoped aggregate; V9 did not index it
-- because the proposal screens only ever sorted by it within an already-narrow result set.
CREATE INDEX idx_programs_proposed_date ON programs (proposed_date);

-- CvSU academic calendar: two semesters plus a midyear term per academic year.
-- Seeded across the previous, current and next AY so the selector is never a single dead option and
-- the dashboard has an empty-but-valid period to render (proving the empty states work).
INSERT INTO academic_periods (id, label, starts_on, ends_on, is_current, created_at) VALUES
    ('50000000-0000-0000-0000-000000000001', 'S1 AY 2025-2026', '2025-08-01', '2025-12-31', false, CURRENT_TIMESTAMP),
    ('50000000-0000-0000-0000-000000000002', 'S2 AY 2025-2026', '2026-01-01', '2026-05-31', false, CURRENT_TIMESTAMP),
    ('50000000-0000-0000-0000-000000000003', 'Midyear AY 2025-2026', '2026-06-01', '2026-07-31', false, CURRENT_TIMESTAMP),
    ('50000000-0000-0000-0000-000000000004', 'S1 AY 2026-2027', '2026-08-01', '2026-12-31', true, CURRENT_TIMESTAMP),
    ('50000000-0000-0000-0000-000000000005', 'S2 AY 2026-2027', '2027-01-01', '2027-05-31', false, CURRENT_TIMESTAMP),
    ('50000000-0000-0000-0000-000000000006', 'Midyear AY 2026-2027', '2027-06-01', '2027-07-31', false, CURRENT_TIMESTAMP);
