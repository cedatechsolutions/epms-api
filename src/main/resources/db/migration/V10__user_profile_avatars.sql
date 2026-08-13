-- V10: Self-service profile photos.
--
-- A user may upload one avatar for their own account (Profile Settings). Bytes live in the
-- configured storage root like every other upload (V4 documents); the row only carries the
-- storage-relative path and the metadata needed to serve it back:
--
--   * avatar_path       — storage-relative path, NULL when the user has no photo.
--   * avatar_mime_type  — image/jpeg or image/png (validated on upload).
--   * avatar_updated_at — doubles as the "has a photo" flag exposed to clients and as the
--                         cache key they use when re-fetching after a replace.
--
-- Portable across H2 (PostgreSQL mode) and PostgreSQL: one column per statement, varchar sizes
-- matching community_documents.

ALTER TABLE users ADD COLUMN avatar_path varchar(1024);
ALTER TABLE users ADD COLUMN avatar_mime_type varchar(255);
ALTER TABLE users ADD COLUMN avatar_updated_at timestamp(6) with time zone;
