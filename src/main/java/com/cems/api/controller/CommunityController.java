package com.cems.api.controller;

import com.cems.api.dto.ApiResponse;
import com.cems.api.dto.CommunityDocumentResponse;
import com.cems.api.dto.CommunityHistoryEntry;
import com.cems.api.dto.CommunityListQuery;
import com.cems.api.dto.CommunityRequest;
import com.cems.api.dto.CommunityResponse;
import com.cems.api.dto.CommunityStatsResponse;
import com.cems.api.dto.CommunitySummaryResponse;
import com.cems.api.dto.PaginatedResponse;
import com.cems.api.service.CommunityService;
import com.cems.api.service.CommunityService.DocumentDownload;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/communities")
public class CommunityController {

    private final CommunityService communityService;

    public CommunityController(CommunityService communityService) {
        this.communityService = communityService;
    }

    @PreAuthorize("@permissions.canViewCommunities()")
    @GetMapping
    public ResponseEntity<PaginatedResponse<CommunitySummaryResponse>> list(CommunityListQuery query,
            HttpServletRequest request) {
        return ResponseEntity.ok(new PaginatedResponse<>(
                communityService.list(query),
                request.getRequestURI()));
    }

    @PreAuthorize("@permissions.canViewCommunities()")
    @GetMapping("/stats")
    public ResponseEntity<CommunityStatsResponse> stats() {
        return ResponseEntity.ok(communityService.getStats());
    }

    @PreAuthorize("@permissions.canViewCommunities()")
    @GetMapping("/{id}")
    public ResponseEntity<CommunityResponse> getById(@PathVariable String id) {
        return ResponseEntity.ok(communityService.getById(id));
    }

    @PreAuthorize("@permissions.canViewCommunities()")
    @GetMapping("/{id}/history")
    public ResponseEntity<List<CommunityHistoryEntry>> history(@PathVariable String id) {
        return ResponseEntity.ok(communityService.getHistory(id));
    }

    @PreAuthorize("@permissions.canManageCommunities()")
    @PostMapping
    public ResponseEntity<CommunityResponse> create(@Valid @RequestBody CommunityRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(communityService.create(request));
    }

    @PreAuthorize("@permissions.canManageCommunities()")
    @PatchMapping("/{id}")
    public ResponseEntity<CommunityResponse> update(@PathVariable String id,
            @Valid @RequestBody CommunityRequest request) {
        return ResponseEntity.ok(communityService.update(id, request));
    }

    @PreAuthorize("@permissions.canManageCommunities()")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse> delete(@PathVariable String id) {
        communityService.delete(id);
        return ResponseEntity.ok(new ApiResponse("Community deleted successfully."));
    }

    // --- documents ---

    @PreAuthorize("@permissions.canViewCommunities()")
    @GetMapping("/{id}/documents")
    public ResponseEntity<List<CommunityDocumentResponse>> listDocuments(@PathVariable String id) {
        return ResponseEntity.ok(communityService.listDocuments(id));
    }

    @PreAuthorize("@permissions.canManageCommunities()")
    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CommunityDocumentResponse> uploadDocument(@PathVariable String id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "docType", required = false) String docType) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(communityService.addDocument(id, file, docType));
    }

    @PreAuthorize("@permissions.canViewCommunities()")
    @GetMapping("/{id}/documents/{documentId}")
    public ResponseEntity<org.springframework.core.io.Resource> downloadDocument(@PathVariable String id,
            @PathVariable String documentId) {
        DocumentDownload download = communityService.loadDocument(id, documentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + sanitizeFilename(download.filename()) + "\"")
                .body(download.resource());
    }

    @PreAuthorize("@permissions.canManageCommunities()")
    @DeleteMapping("/{id}/documents/{documentId}")
    public ResponseEntity<ApiResponse> deleteDocument(@PathVariable String id,
            @PathVariable String documentId) {
        communityService.deleteDocument(id, documentId);
        return ResponseEntity.ok(new ApiResponse("Document deleted successfully."));
    }

    /** Strips characters that would break the Content-Disposition header. */
    private String sanitizeFilename(String filename) {
        return filename == null ? "download" : filename.replaceAll("[\"\\r\\n]", "_");
    }
}
