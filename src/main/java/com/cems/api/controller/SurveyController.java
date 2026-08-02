package com.cems.api.controller;

import com.cems.api.dto.ApiResponse;
import com.cems.api.dto.GeneratedReportResponse;
import com.cems.api.dto.PaginatedResponse;
import com.cems.api.dto.QuestionRequest;
import com.cems.api.dto.QuestionResponse;
import com.cems.api.dto.SurveyListQuery;
import com.cems.api.dto.SurveyRequest;
import com.cems.api.dto.SurveyResponse;
import com.cems.api.dto.SurveyResultsResponse;
import com.cems.api.dto.SurveySummaryResponse;
import com.cems.api.service.Qf23ReportService.SignatoryOverrides;
import com.cems.api.service.SurveyReportService;
import com.cems.api.service.SurveyReportService.GeneratedFile;
import com.cems.api.service.SurveyResultsService;
import com.cems.api.service.SurveyService;
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

import java.util.List;

/**
 * Survey builder endpoints (spec Module 3 §1). Reads require {@code canViewAssessments}; writes
 * require {@code canCreateSurveys}, with per-survey ownership enforced in the service layer.
 */
@RestController
@RequestMapping("/api/surveys")
public class SurveyController {

    private final SurveyService surveyService;
    private final SurveyResultsService resultsService;
    private final SurveyReportService reportService;

    public SurveyController(SurveyService surveyService,
            SurveyResultsService resultsService,
            SurveyReportService reportService) {
        this.surveyService = surveyService;
        this.resultsService = resultsService;
        this.reportService = reportService;
    }

    @PreAuthorize("@permissions.canViewAssessments()")
    @GetMapping
    public ResponseEntity<PaginatedResponse<SurveySummaryResponse>> list(SurveyListQuery query,
            HttpServletRequest request) {
        return ResponseEntity.ok(new PaginatedResponse<>(surveyService.list(query), request.getRequestURI()));
    }

    @PreAuthorize("@permissions.canViewAssessments()")
    @GetMapping("/{id}")
    public ResponseEntity<SurveyResponse> getById(@PathVariable String id) {
        return ResponseEntity.ok(surveyService.getById(id));
    }

    @PreAuthorize("@permissions.canCreateSurveys()")
    @PostMapping
    public ResponseEntity<SurveyResponse> create(@Valid @RequestBody SurveyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(surveyService.create(request));
    }

    @PreAuthorize("@permissions.canCreateSurveys()")
    @PatchMapping("/{id}")
    public ResponseEntity<SurveyResponse> update(@PathVariable String id, @Valid @RequestBody SurveyRequest request) {
        return ResponseEntity.ok(surveyService.update(id, request));
    }

    @PreAuthorize("@permissions.canCreateSurveys()")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse> delete(@PathVariable String id) {
        surveyService.delete(id);
        return ResponseEntity.ok(new ApiResponse("Survey deleted successfully."));
    }

    // --- lifecycle ---

    @PreAuthorize("@permissions.canCreateSurveys()")
    @PostMapping("/{id}/deploy")
    public ResponseEntity<SurveyResponse> deploy(@PathVariable String id) {
        return ResponseEntity.ok(surveyService.deploy(id));
    }

    @PreAuthorize("@permissions.canCreateSurveys()")
    @PostMapping("/{id}/close")
    public ResponseEntity<SurveyResponse> close(@PathVariable String id) {
        return ResponseEntity.ok(surveyService.close(id));
    }

    // --- questions ---

    @PreAuthorize("@permissions.canCreateSurveys()")
    @PostMapping("/{id}/questions")
    public ResponseEntity<QuestionResponse> addQuestion(@PathVariable String id,
            @Valid @RequestBody QuestionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(surveyService.addQuestion(id, request));
    }

    @PreAuthorize("@permissions.canCreateSurveys()")
    @PatchMapping("/{id}/questions/{questionId}")
    public ResponseEntity<QuestionResponse> updateQuestion(@PathVariable String id,
            @PathVariable String questionId,
            @Valid @RequestBody QuestionRequest request) {
        return ResponseEntity.ok(surveyService.updateQuestion(id, questionId, request));
    }

    @PreAuthorize("@permissions.canCreateSurveys()")
    @DeleteMapping("/{id}/questions/{questionId}")
    public ResponseEntity<ApiResponse> deleteQuestion(@PathVariable String id, @PathVariable String questionId) {
        surveyService.deleteQuestion(id, questionId);
        return ResponseEntity.ok(new ApiResponse("Question deleted successfully."));
    }

    // --- results, finalize, exports (spec Module 3 §4/§5) ---

    @PreAuthorize("@permissions.canViewAssessments()")
    @GetMapping("/{id}/results")
    public ResponseEntity<SurveyResultsResponse> results(@PathVariable String id) {
        return ResponseEntity.ok(resultsService.getResults(id));
    }

    @PreAuthorize("@permissions.canCreateSurveys()")
    @PostMapping("/{id}/finalize")
    public ResponseEntity<SurveyResultsResponse> finalizeResults(@PathVariable String id) {
        return ResponseEntity.ok(resultsService.finalizeResults(id));
    }

    @PreAuthorize("@permissions.canViewAssessments()")
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id,
            @RequestParam(defaultValue = "xlsx") String format) {
        GeneratedFile file = switch (format.toLowerCase(java.util.Locale.ROOT)) {
            case "xlsx" -> reportService.exportXlsx(id);
            case "pdf" -> reportService.exportPdf(id);
            default -> throw new IllegalArgumentException("Export format must be xlsx or pdf.");
        };
        return download(file);
    }

    @PreAuthorize("@permissions.canCreateSurveys()")
    @PostMapping("/{id}/report-qf23")
    public ResponseEntity<byte[]> generateQf23(@PathVariable String id,
            @RequestBody(required = false) SignatoryOverrides overrides) {
        return download(reportService.generateQf23(
                id, overrides == null ? SignatoryOverrides.empty() : overrides));
    }

    @PreAuthorize("@permissions.canViewAssessments()")
    @GetMapping("/{id}/reports")
    public ResponseEntity<List<GeneratedReportResponse>> listReports(@PathVariable String id) {
        return ResponseEntity.ok(reportService.listQf23Reports(id));
    }

    @PreAuthorize("@permissions.canViewAssessments()")
    @GetMapping("/{id}/reports/{reportId}")
    public ResponseEntity<byte[]> downloadReport(@PathVariable String id, @PathVariable String reportId) {
        return download(reportService.downloadArchivedReport(id, reportId));
    }

    private ResponseEntity<byte[]> download(GeneratedFile file) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.filename() + "\"")
                .contentLength(file.content().length)
                .body(file.content());
    }
}
