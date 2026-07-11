-- V2: Replace the legacy 3-role set (ROLE_SUPER_ADMIN/ROLE_ADMIN/ROLE_USER) with the
-- six EPMS domain roles (spec §2.1) and remap any existing user assignments.
--
-- Portable across H2 (PostgreSQL mode, local/dev) and PostgreSQL (prod).
-- Idempotent: safe to re-run; on a fresh H2 database the roles table is empty at
-- migration time (the seeder runs afterwards), so the UPDATE/DELETE steps are no-ops
-- and only the INSERTs take effect. On an existing PostgreSQL database with legacy
-- data, the remap preserves each account's access under the new role names.
--
-- Deterministic role ids keep the seeder (findByName) and any FK references stable.

-- 1. Insert the six domain roles (skip any already present).
INSERT INTO roles (id, name, description)
SELECT '10000000-0000-0000-0000-000000000001', 'admin',
       'System administrator. Full access, user and role management, program-type library, scoring matrix.'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = 'admin');

INSERT INTO roles (id, name, description)
SELECT '10000000-0000-0000-0000-000000000002', 'campus_admin',
       'Campus Administrator. Final approval authority; read access to everything.'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = 'campus_admin');

INSERT INTO roles (id, name, description)
SELECT '10000000-0000-0000-0000-000000000003', 'campus_extension_coordinator',
       'Campus Extension Coordinator. Recommends approval (stage 3); oversees all extension activity.'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = 'campus_extension_coordinator');

INSERT INTO roles (id, name, description)
SELECT '10000000-0000-0000-0000-000000000004', 'extension_coordinator',
       'Extension Coordinator. Reviews proposals (stage 2); manages communities, assessments, recommendations.'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = 'extension_coordinator');

INSERT INTO roles (id, name, description)
SELECT '10000000-0000-0000-0000-000000000005', 'faculty',
       'Faculty Extensionist / Project Leader. Creates proposals, conducts assessments, records attendance.'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = 'faculty');

INSERT INTO roles (id, name, description)
SELECT '10000000-0000-0000-0000-000000000006', 'student_volunteer',
       'Student volunteer. Views assigned activities and helps encode attendance; cannot approve or create proposals.'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = 'student_volunteer');

-- 2. Remap legacy assignments onto the new roles
--    (ROLE_SUPER_ADMIN -> admin, ROLE_ADMIN -> extension_coordinator, ROLE_USER -> faculty).
UPDATE user_roles
SET role_id = (SELECT id FROM roles WHERE name = 'admin')
WHERE role_id IN (SELECT id FROM roles WHERE name = 'ROLE_SUPER_ADMIN');

UPDATE user_roles
SET role_id = (SELECT id FROM roles WHERE name = 'extension_coordinator')
WHERE role_id IN (SELECT id FROM roles WHERE name = 'ROLE_ADMIN');

UPDATE user_roles
SET role_id = (SELECT id FROM roles WHERE name = 'faculty')
WHERE role_id IN (SELECT id FROM roles WHERE name = 'ROLE_USER');

-- 3. Drop the legacy roles now that no assignment references them.
DELETE FROM roles WHERE name IN ('ROLE_SUPER_ADMIN', 'ROLE_ADMIN', 'ROLE_USER');
