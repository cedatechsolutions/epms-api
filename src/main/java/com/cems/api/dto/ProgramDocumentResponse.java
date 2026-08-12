package com.cems.api.dto;

import com.cems.api.entity.ProgramDocument;

import java.time.Instant;

/**
 * Proposal attachment metadata (spec §3.5). {@code filePath} is deliberately absent — clients
 * download through the authorized route by id, never by storage path.
 */
public record ProgramDocumentResponse(
        String id,
        String originalFilename,
        String mimeType,
        long sizeBytes,
        String docType,
        String uploadedBy,
        Instant createdAt) {

    public static ProgramDocumentResponse fromEntity(ProgramDocument document) {
        return new ProgramDocumentResponse(
                document.getId(),
                document.getOriginalFilename(),
                document.getMimeType(),
                document.getSizeBytes(),
                document.getDocType(),
                document.getUploadedBy(),
                document.getCreatedAt());
    }
}
