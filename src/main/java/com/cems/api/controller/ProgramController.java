package com.cems.api.controller;

import com.cems.api.dto.ApiResponse;
import com.cems.api.dto.PaginatedResponse;
import com.cems.api.dto.ProgramApprovalResponse;
import com.cems.api.dto.ProgramDocumentResponse;
import com.cems.api.dto.ProgramListQuery;
import com.cems.api.dto.ProgramMemberRequest;
import com.cems.api.dto.ProgramMemberResponse;
import com.cems.api.dto.ProgramRequest;
import com.cems.api.dto.ProgramResponse;
import com.cems.api.dto.ProgramStageActionRequest;
import com.cems.api.dto.ProgramStatsResponse;
import com.cems.api.dto.ProgramSummaryResponse;
import com.cems.api.service.ProgramMemberService;
import com.cems.api.service.ProgramService;
import com.cems.api.service.ProgramService.DocumentDownload;
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

/**
 * Programs and the four-stage approval chain (spec Module 5a API surface).
 *
 * <p>Each stage endpoint is gated by the single role that owns it — see {@code Permissions}, where
 * the deliberate exclusion of {@code admin} is documented. A caller holding the role but hitting a
 * proposal at the wrong stage is rejected by the state machine with 409, not 403.
 */
@RestController
@RequestMapping("/api/programs")
public class ProgramController {

    private final ProgramService programService;
    private final ProgramMemberService programMemberService;

    public ProgramController(ProgramService programService,
            ProgramMemberService programMemberService) {
        this.programService = programService;
        this.programMemberService = programMemberService;
    }

    // --- reads ---

    @PreAuthorize("@permissions.canViewPrograms()")
    @GetMapping
    public ResponseEntity<PaginatedResponse<ProgramSummaryResponse>> list(ProgramListQuery query,
            HttpServletRequest request) {
        return ResponseEntity.ok(new PaginatedResponse<>(
                programService.list(query),
                request.getRequestURI()));
    }

    @PreAuthorize("@permissions.canViewPrograms()")
    /**
     * @param periodId must match the {@code periodId} the list was fetched with, so the tab badges
     *                 count the same programs the rows show. Omitted means all periods.
     */
    @GetMapping("/stats")
    public ResponseEntity<ProgramStatsResponse> stats(
            @RequestParam(name = "periodId", required = false) String periodId) {
        return ResponseEntity.ok(programService.getStats(periodId));
    }

    @PreAuthorize("@permissions.canViewPrograms()")
    @GetMapping("/{id}")
    public ResponseEntity<ProgramResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(programService.getById(id));
    }

    @PreAuthorize("@permissions.canViewPrograms()")
    @GetMapping("/{id}/approvals")
    public ResponseEntity<List<ProgramApprovalResponse>> approvals(@PathVariable String id) {
        return ResponseEntity.ok(programService.getApprovals(id));
    }

    // --- writes ---

    @PreAuthorize("@permissions.canCreatePrograms()")
    @PostMapping
    public ResponseEntity<ProgramResponse> create(@Valid @RequestBody ProgramRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(programService.create(request));
    }

    @PreAuthorize("@permissions.canCreatePrograms()")
    @PatchMapping("/{id}")
    public ResponseEntity<ProgramResponse> update(@PathVariable String id,
            @Valid @RequestBody ProgramRequest request) {
        return ResponseEntity.ok(programService.update(id, request));
    }

    @PreAuthorize("@permissions.canCreatePrograms()")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse> delete(@PathVariable String id) {
        programService.delete(id);
        return ResponseEntity.ok(new ApiResponse("Proposal deleted."));
    }

    // --- approval chain ---

    /** Stage 1 — the owning faculty submits for review. */
    @PreAuthorize("@permissions.canCreatePrograms()")
    @PostMapping("/{id}/submit")
    public ResponseEntity<ProgramResponse> submit(@PathVariable String id) {
        return ResponseEntity.ok(programService.submit(id));
    }

    /** Stage 2 — extension coordinator: {@code {action: note|return, comment}}. */
    @PreAuthorize("@permissions.canReviewProposals()")
    @PostMapping("/{id}/review")
    public ResponseEntity<ProgramResponse> review(@PathVariable String id,
            @RequestBody(required = false) ProgramStageActionRequest request) {
        return ResponseEntity.ok(programService.review(id, request));
    }

    /** Stage 3 — campus extension coordinator: {@code {action: recommend|return, comment}}. */
    @PreAuthorize("@permissions.canRecommendApproval()")
    @PostMapping("/{id}/recommend")
    public ResponseEntity<ProgramResponse> recommend(@PathVariable String id,
            @RequestBody(required = false) ProgramStageActionRequest request) {
        return ResponseEntity.ok(programService.recommend(id, request));
    }

    /** Stage 4 — campus administrator: {@code {action: approve|return, comment, budgetApproved}}. */
    @PreAuthorize("@permissions.canFinalApprove()")
    @PostMapping("/{id}/approve")
    public ResponseEntity<ProgramResponse> approve(@PathVariable String id,
            @RequestBody(required = false) ProgramStageActionRequest request) {
        return ResponseEntity.ok(programService.approve(id, request));
    }

    // --- team assignment (spec Module 5 §1 "own + assigned") ---

    @PreAuthorize("@permissions.canViewPrograms()")
    @GetMapping("/{id}/members")
    public ResponseEntity<List<ProgramMemberResponse>> listMembers(@PathVariable String id) {
        return ResponseEntity.ok(programMemberService.list(id));
    }

    @PreAuthorize("@permissions.canCreatePrograms()")
    @PostMapping("/{id}/members")
    public ResponseEntity<ProgramMemberResponse> addMember(@PathVariable String id,
            @Valid @RequestBody ProgramMemberRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(programMemberService.add(id, request));
    }

    @PreAuthorize("@permissions.canCreatePrograms()")
    @DeleteMapping("/{id}/members/{memberId}")
    public ResponseEntity<ApiResponse> removeMember(@PathVariable String id,
            @PathVariable String memberId) {
        programMemberService.remove(id, memberId);
        return ResponseEntity.ok(new ApiResponse("Assignment removed."));
    }

    // --- documents ---

    @PreAuthorize("@permissions.canViewPrograms()")
    @GetMapping("/{id}/documents")
    public ResponseEntity<List<ProgramDocumentResponse>> listDocuments(@PathVariable String id) {
        return ResponseEntity.ok(programService.listDocuments(id));
    }

    @PreAuthorize("@permissions.canCreatePrograms()")
    @PostMapping("/{id}/documents")
    public ResponseEntity<ProgramDocumentResponse> addDocument(@PathVariable String id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "docType", required = false) String docType) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(programService.addDocument(id, file, docType));
    }

    @PreAuthorize("@permissions.canViewPrograms()")
    @GetMapping("/{id}/documents/{documentId}")
    public ResponseEntity<org.springframework.core.io.Resource> downloadDocument(@PathVariable String id,
            @PathVariable String documentId) {
        DocumentDownload download = programService.loadDocument(id, documentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + download.filename() + "\"")
                .contentType(MediaType.parseMediaType(download.mimeType()))
                .contentLength(download.sizeBytes())
                .body(download.resource());
    }

    @PreAuthorize("@permissions.canCreatePrograms()")
    @DeleteMapping("/{id}/documents/{documentId}")
    public ResponseEntity<ApiResponse> deleteDocument(@PathVariable String id,
            @PathVariable String documentId) {
        programService.deleteDocument(id, documentId);
        return ResponseEntity.ok(new ApiResponse("Document deleted."));
    }
}
