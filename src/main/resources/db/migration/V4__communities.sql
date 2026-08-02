-- V4: Community Profiling master data (spec Module 2, data model §3.2).
--
-- Portable across H2 (PostgreSQL mode, local/dev) and PostgreSQL (prod):
--   * "timestamp with time zone" maps to timestamptz on both.
--   * CHECK constraints on enumerated string columns keep classification/doc_type honest.
-- UUID varchar primary keys stay consistent with the existing schema (plan §0).

-- 1. Partner communities — GAD-ready population data + soft delete (spec §3.2).
CREATE TABLE communities (
    id varchar(255) NOT NULL,
    name varchar(255) NOT NULL,
    barangay_code varchar(255),
    municipality varchar(255) NOT NULL,
    province varchar(255) NOT NULL,
    classification varchar(32) NOT NULL,
    estimated_population integer,
    household_count integer,
    population_male integer,
    population_female integer,
    contact_person_name varchar(255),
    contact_person_designation varchar(255),
    contact_person_phone varchar(255),
    notes text,
    created_by varchar(255),
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    CONSTRAINT communities_pkey PRIMARY KEY (id),
    CONSTRAINT chk_communities_classification CHECK (
        classification IN ('urban_poor', 'rural', 'urban', 'coastal', 'other')),
    CONSTRAINT fk_communities_created_by FOREIGN KEY (created_by) REFERENCES users (id)
);
CREATE INDEX idx_communities_municipality ON communities (municipality);
CREATE INDEX idx_communities_deleted_at ON communities (deleted_at);

-- 2. Sectors — seeded lookup, admin-manageable via is_active (spec §3.2 line 122).
CREATE TABLE sectors (
    id varchar(255) NOT NULL,
    name varchar(255) NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    CONSTRAINT sectors_pkey PRIMARY KEY (id),
    CONSTRAINT uk_sectors_name UNIQUE (name)
);

-- Deterministic ids keep FK references and seeding stable across environments.
INSERT INTO sectors (id, name, is_active) VALUES
    ('20000000-0000-0000-0000-000000000001', 'Youth', true),
    ('20000000-0000-0000-0000-000000000002', 'Women', true),
    ('20000000-0000-0000-0000-000000000003', '4Ps', true),
    ('20000000-0000-0000-0000-000000000004', 'MSMEs', true),
    ('20000000-0000-0000-0000-000000000005', 'Senior Citizens', true),
    ('20000000-0000-0000-0000-000000000006', 'PWD', true),
    ('20000000-0000-0000-0000-000000000007', 'Farmers/Fisherfolk', true),
    ('20000000-0000-0000-0000-000000000008', 'OSY', true);

-- 3. Community <-> sector tags (pivot).
CREATE TABLE community_sector (
    community_id varchar(255) NOT NULL,
    sector_id varchar(255) NOT NULL,
    CONSTRAINT community_sector_pkey PRIMARY KEY (community_id, sector_id),
    CONSTRAINT fk_community_sector_community FOREIGN KEY (community_id) REFERENCES communities (id),
    CONSTRAINT fk_community_sector_sector FOREIGN KEY (sector_id) REFERENCES sectors (id)
);
CREATE INDEX idx_community_sector_sector ON community_sector (sector_id);

-- 4. Community documents — MOAs, certifications, photos (spec §3.2; max 10 MB, pdf/docx/xlsx/jpg/png).
CREATE TABLE community_documents (
    id varchar(255) NOT NULL,
    community_id varchar(255) NOT NULL,
    file_path varchar(1024) NOT NULL,
    original_filename varchar(512) NOT NULL,
    mime_type varchar(255) NOT NULL,
    size_bytes bigint NOT NULL,
    doc_type varchar(32) NOT NULL,
    uploaded_by varchar(255),
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT community_documents_pkey PRIMARY KEY (id),
    CONSTRAINT chk_community_documents_doc_type CHECK (
        doc_type IN ('moa', 'certification', 'photo', 'other')),
    CONSTRAINT fk_community_documents_community FOREIGN KEY (community_id) REFERENCES communities (id),
    CONSTRAINT fk_community_documents_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users (id)
);
CREATE INDEX idx_community_documents_community ON community_documents (community_id);
