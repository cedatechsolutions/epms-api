-- V8: Recommendation Engine (spec Module 4, data model §3.4).
--
-- A transparent weighted scoring matrix, NOT machine learning (spec §Module 4 purpose) — every score
-- must stay reproducible from the stored breakdown, which is why score_breakdown is persisted per row.
--
-- Portable across H2 (PostgreSQL mode) and PostgreSQL, matching V5/V7 conventions:
--   * JSON is stored as TEXT and (de)serialized in the service layer (as activity_logs.metadata does).
--   * "rank" is a reserved word in some engines, so the column is rank_position (as in V7).

-- 1. Program types — the admin-managed library the engine matches against.
CREATE TABLE program_types (
    id varchar(255) NOT NULL,
    name varchar(255) NOT NULL,
    description text,
    default_duration varchar(128),
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone,
    CONSTRAINT program_types_pkey PRIMARY KEY (id),
    CONSTRAINT uk_program_types_name UNIQUE (name)
);
CREATE INDEX idx_program_types_is_active ON program_types (is_active);

-- 2. The scoring matrix: how strongly a program type addresses a need category (0.00–5.00).
--    An absent row means weight 0 — the type does not address that need at all.
CREATE TABLE program_type_need_weights (
    id varchar(255) NOT NULL,
    program_type_id varchar(255) NOT NULL,
    need_category_id varchar(255) NOT NULL,
    weight numeric(4, 2) NOT NULL DEFAULT 0.0,
    CONSTRAINT program_type_need_weights_pkey PRIMARY KEY (id),
    CONSTRAINT uk_ptnw_type_category UNIQUE (program_type_id, need_category_id),
    CONSTRAINT chk_ptnw_weight CHECK (weight >= 0 AND weight <= 5),
    CONSTRAINT fk_ptnw_program_type FOREIGN KEY (program_type_id) REFERENCES program_types (id),
    CONSTRAINT fk_ptnw_need_category FOREIGN KEY (need_category_id) REFERENCES need_categories (id)
);
CREATE INDEX idx_ptnw_program_type ON program_type_need_weights (program_type_id);

-- 3. Which sectors a program type fits. Intersecting the community's sectors earns the +10% bonus.
CREATE TABLE program_type_sector (
    program_type_id varchar(255) NOT NULL,
    sector_id varchar(255) NOT NULL,
    CONSTRAINT program_type_sector_pkey PRIMARY KEY (program_type_id, sector_id),
    CONSTRAINT fk_pts_program_type FOREIGN KEY (program_type_id) REFERENCES program_types (id),
    CONSTRAINT fk_pts_sector FOREIGN KEY (sector_id) REFERENCES sectors (id)
);

-- 4. Generated recommendations. Re-running the engine replaces 'pending' rows only — decided ones are
--    part of the audit trail (spec Module 4 AC 1). decided_by/at/note record who ruled and why.
CREATE TABLE recommendations (
    id varchar(255) NOT NULL,
    survey_id varchar(255) NOT NULL,
    program_type_id varchar(255) NOT NULL,
    match_score numeric(5, 2) NOT NULL,
    score_breakdown text,
    rank_position integer NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'pending',
    decided_by varchar(255),
    decided_at timestamp(6) with time zone,
    decision_note text,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT recommendations_pkey PRIMARY KEY (id),
    CONSTRAINT chk_recommendations_status CHECK (
        status IN ('pending', 'accepted', 'modified', 'rejected')),
    CONSTRAINT fk_recommendations_survey FOREIGN KEY (survey_id) REFERENCES surveys (id),
    CONSTRAINT fk_recommendations_program_type FOREIGN KEY (program_type_id) REFERENCES program_types (id),
    CONSTRAINT fk_recommendations_decided_by FOREIGN KEY (decided_by) REFERENCES users (id)
);
CREATE INDEX idx_recommendations_survey ON recommendations (survey_id);
CREATE INDEX idx_recommendations_status ON recommendations (status);

-- 5. Seed library (spec §5.4: 5 program types with a sensible matrix, so the screens render with data
--    on first run). Deterministic ids keep scoring fixtures stable across environments; the official
--    Extension Services Center list can replace these through the admin screens (open question #3).
INSERT INTO program_types (id, name, description, default_duration, is_active, created_at) VALUES
    ('40000000-0000-0000-0000-000000000001', 'Health & Wellness Caravan',
     'Medical, dental and wellness services delivered on-site in the partner community.',
     '1-2 days', true, CURRENT_TIMESTAMP),
    ('40000000-0000-0000-0000-000000000002', 'Livelihood & Skills Training',
     'Hands-on skills training and enterprise coaching toward household income generation.',
     '4-8 weeks', true, CURRENT_TIMESTAMP),
    ('40000000-0000-0000-0000-000000000003', 'Literacy & Tutorial Program',
     'Supplementary instruction and literacy sessions for learners and out-of-school youth.',
     '1 semester', true, CURRENT_TIMESTAMP),
    ('40000000-0000-0000-0000-000000000004', 'Environmental Protection Drive',
     'Clean-up, waste segregation, tree planting and environmental awareness campaigns.',
     '1-3 days', true, CURRENT_TIMESTAMP),
    ('40000000-0000-0000-0000-000000000005', 'Governance & Capacity Building',
     'Seminars strengthening barangay governance, disaster preparedness and organisational capacity.',
     '2-4 weeks', true, CURRENT_TIMESTAMP);

-- Matrix weights. Need categories (V5): 1 Health, 2 Livelihood, 3 Education, 4 Environment,
-- 5 Safety, 6 Governance, 7 General.
INSERT INTO program_type_need_weights (id, program_type_id, need_category_id, weight) VALUES
    -- Health & Wellness Caravan
    ('41000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001',
     '30000000-0000-0000-0000-000000000001', 5.00),
    ('41000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000001',
     '30000000-0000-0000-0000-000000000007', 1.00),
    -- Livelihood & Skills Training
    ('41000000-0000-0000-0000-000000000003', '40000000-0000-0000-0000-000000000002',
     '30000000-0000-0000-0000-000000000002', 5.00),
    ('41000000-0000-0000-0000-000000000004', '40000000-0000-0000-0000-000000000002',
     '30000000-0000-0000-0000-000000000003', 2.00),
    -- Literacy & Tutorial Program
    ('41000000-0000-0000-0000-000000000005', '40000000-0000-0000-0000-000000000003',
     '30000000-0000-0000-0000-000000000003', 5.00),
    ('41000000-0000-0000-0000-000000000006', '40000000-0000-0000-0000-000000000003',
     '30000000-0000-0000-0000-000000000007', 1.00),
    -- Environmental Protection Drive
    ('41000000-0000-0000-0000-000000000007', '40000000-0000-0000-0000-000000000004',
     '30000000-0000-0000-0000-000000000004', 5.00),
    ('41000000-0000-0000-0000-000000000008', '40000000-0000-0000-0000-000000000004',
     '30000000-0000-0000-0000-000000000005', 2.00),
    -- Governance & Capacity Building
    ('41000000-0000-0000-0000-000000000009', '40000000-0000-0000-0000-000000000005',
     '30000000-0000-0000-0000-000000000006', 5.00),
    ('41000000-0000-0000-0000-000000000010', '40000000-0000-0000-0000-000000000005',
     '30000000-0000-0000-0000-000000000005', 2.50),
    ('41000000-0000-0000-0000-000000000011', '40000000-0000-0000-0000-000000000005',
     '30000000-0000-0000-0000-000000000007', 1.50);

-- Sector fit. Sectors (V4): 1 Youth, 2 Women, 3 4Ps, 4 MSMEs, 5 Senior Citizens, 6 PWD,
-- 7 Farmers/Fisherfolk, 8 OSY.
INSERT INTO program_type_sector (program_type_id, sector_id) VALUES
    ('40000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000005'),
    ('40000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000006'),
    ('40000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002'),
    ('40000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000002'),
    ('40000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000003'),
    ('40000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000004'),
    ('40000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000008'),
    ('40000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000001'),
    ('40000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000008'),
    ('40000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000003'),
    ('40000000-0000-0000-0000-000000000004', '20000000-0000-0000-0000-000000000001'),
    ('40000000-0000-0000-0000-000000000004', '20000000-0000-0000-0000-000000000007'),
    ('40000000-0000-0000-0000-000000000005', '20000000-0000-0000-0000-000000000002'),
    ('40000000-0000-0000-0000-000000000005', '20000000-0000-0000-0000-000000000005'),
    ('40000000-0000-0000-0000-000000000005', '20000000-0000-0000-0000-000000000007');
