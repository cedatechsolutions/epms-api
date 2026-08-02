package com.cems.api.storage;

/**
 * Metadata returned by {@link StorageService#store} after a file's bytes are persisted.
 *
 * @param path             storage-relative path used later to load/delete the bytes (never exposed to clients)
 * @param originalFilename the client-supplied filename, preserved for download
 * @param mimeType         the validated content type
 * @param sizeBytes        the stored size in bytes
 */
public record StoredFile(String path, String originalFilename, String mimeType, long sizeBytes) {
}
