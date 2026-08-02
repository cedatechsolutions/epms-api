package com.cems.api.dto;

import com.cems.api.entity.CommunityDocument;

import java.time.Instant;

/**
 * Community document metadata (spec §3.2). The storage path is intentionally omitted — clients
 * download via {@code GET /api/communities/{id}/documents/{docId}} rather than a direct path.
 */
public record CommunityDocumentResponse(
        String id,
        String originalFilename,
        String mimeType,
        long sizeBytes,
        String docType,
        Instant createdAt) {

    public static CommunityDocumentResponse fromEntity(CommunityDocument document) {
        return new CommunityDocumentResponse(
                document.getId(),
                document.getOriginalFilename(),
                document.getMimeType(),
                document.getSizeBytes(),
                document.getDocType(),
                document.getCreatedAt());
    }
}
