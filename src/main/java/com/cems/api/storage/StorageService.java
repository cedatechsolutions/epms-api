package com.cems.api.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * Abstraction over file storage. The local-disk implementation keeps bytes outside the webroot
 * with DB-held metadata (plan §0); swap for an S3-backed impl later without touching callers.
 */
public interface StorageService {

    /**
     * Validates and stores an uploaded file under {@code subdir}, returning its metadata.
     *
     * @throws IllegalArgumentException if the file is empty, too large, or of a disallowed type
     *                                  (surfaced as HTTP 422 by the global handler)
     */
    StoredFile store(MultipartFile file, String subdir);

    /**
     * Stores bytes the system generated itself (reports, exports). Unlike {@link #store}, this
     * bypasses the upload whitelist — the content is trusted because we produced it, not a client.
     */
    StoredFile storeBytes(byte[] content, String originalFilename, String mimeType, String subdir);

    /** Loads previously stored bytes by their storage-relative path. */
    Resource load(String path);

    /** Deletes stored bytes by their storage-relative path; a missing file is not an error. */
    void delete(String path);
}
