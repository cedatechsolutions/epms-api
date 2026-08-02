package com.cems.api.service;

import com.cems.api.dto.GeneratedReportResponse;
import com.cems.api.entity.GeneratedReport;
import com.cems.api.entity.Survey;
import com.cems.api.entity.User;
import com.cems.api.exception.ConflictException;
import com.cems.api.repository.AssessmentResultRepository;
import com.cems.api.repository.GeneratedReportRepository;
import com.cems.api.repository.SurveyRepository;
import com.cems.api.repository.UserRepository;
import com.cems.api.service.Qf23ReportService.SignatoryOverrides;
import com.cems.api.storage.StorageService;
import com.cems.api.storage.StoredFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Orchestrates survey document generation (spec Module 3 §4/§5): produces the file, archives it in
 * {@code generated_reports}, and logs the export (RA 10173 — every data export is auditable).
 *
 * <p>Archived files are immutable: regenerating writes a NEW row rather than overwriting.
 */
@Service
public class SurveyReportService {

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final SurveyRepository surveyRepository;
    private final AssessmentResultRepository resultRepository;
    private final GeneratedReportRepository reportRepository;
    private final UserRepository userRepository;
    private final Qf23ReportService qf23ReportService;
    private final SurveyXlsxExportService xlsxExportService;
    private final SurveyPdfSummaryService pdfSummaryService;
    private final StorageService storageService;
    private final ActivityLogService activityLogService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SurveyReportService(SurveyRepository surveyRepository,
            AssessmentResultRepository resultRepository,
            GeneratedReportRepository reportRepository,
            UserRepository userRepository,
            Qf23ReportService qf23ReportService,
            SurveyXlsxExportService xlsxExportService,
            SurveyPdfSummaryService pdfSummaryService,
            StorageService storageService,
            ActivityLogService activityLogService) {
        this.surveyRepository = surveyRepository;
        this.resultRepository = resultRepository;
        this.reportRepository = reportRepository;
        this.userRepository = userRepository;
        this.qf23ReportService = qf23ReportService;
        this.xlsxExportService = xlsxExportService;
        this.pdfSummaryService = pdfSummaryService;
        this.storageService = storageService;
        this.activityLogService = activityLogService;
    }

    /** A generated document ready to stream back to the caller. */
    public record GeneratedFile(byte[] content, String filename, String contentType) {
    }

    /**
     * Generates the EXTN-QF-23 report and archives it. Requires finalized results — the report
     * quotes the approved numbers, so it cannot be issued from a moving target (spec §4: finalizing
     * "enables Recommendations + report generation").
     */
    public GeneratedFile generateQf23(String surveyId, SignatoryOverrides overrides) {
        Survey survey = findActive(surveyId);
        if (!resultRepository.existsBySurveyId(surveyId)) {
            throw new ConflictException("Finalize the results before generating the EXTN-QF-23 report.");
        }

        byte[] content = qf23ReportService.generate(surveyId, overrides);
        String filename = "EXTN-QF-23_" + slug(survey.getTitle()) + ".docx";

        StoredFile stored = storageService.storeBytes(content, filename, DOCX_MIME, "reports/qf23");

        GeneratedReport report = new GeneratedReport();
        report.setReportType(GeneratedReport.TYPE_NEEDS_ASSESSMENT_QF23);
        report.setScope(toJson(Map.of("surveyId", surveyId, "title", survey.getTitle())));
        report.setFilePath(stored.path());
        report.setFileType("docx");
        report.setGeneratedBy(currentUserId());
        reportRepository.save(report);

        activityLogService.record("survey.report_generated", "survey", surveyId,
                Map.of("reportType", GeneratedReport.TYPE_NEEDS_ASSESSMENT_QF23));

        return new GeneratedFile(content, filename, DOCX_MIME);
    }

    /** XLSX export (raw responses + sex-disaggregated summary). Streamed, not archived. */
    public GeneratedFile exportXlsx(String surveyId) {
        Survey survey = findActive(surveyId);
        byte[] content = xlsxExportService.export(surveyId);
        activityLogService.record("survey.exported", "survey", surveyId, Map.of("format", "xlsx"));
        return new GeneratedFile(content, slug(survey.getTitle()) + "_results.xlsx", XLSX_MIME);
    }

    /** PDF summary of the ranked needs. Streamed, not archived. */
    public GeneratedFile exportPdf(String surveyId) {
        Survey survey = findActive(surveyId);
        byte[] content = pdfSummaryService.export(surveyId);
        activityLogService.record("survey.exported", "survey", surveyId, Map.of("format", "pdf"));
        return new GeneratedFile(content, slug(survey.getTitle()) + "_summary.pdf", "application/pdf");
    }

    /** The QF-23 documents already generated for this survey, newest first. */
    @Transactional(readOnly = true)
    public List<GeneratedReportResponse> listQf23Reports(String surveyId) {
        findActive(surveyId);
        return reportRepository
                .findByReportTypeOrderByCreatedAtDesc(GeneratedReport.TYPE_NEEDS_ASSESSMENT_QF23).stream()
                .filter(report -> report.getScope() != null && report.getScope().contains(surveyId))
                .map(GeneratedReportResponse::fromEntity)
                .toList();
    }

    /** Downloads a previously archived report. Files are immutable once written. */
    @Transactional(readOnly = true)
    public GeneratedFile downloadArchivedReport(String surveyId, String reportId) {
        Survey survey = findActive(surveyId);
        GeneratedReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new NoSuchElementException("Report not found."));
        if (report.getScope() == null || !report.getScope().contains(surveyId)) {
            throw new NoSuchElementException("Report not found.");
        }

        Resource resource = storageService.load(report.getFilePath());
        try (var stream = resource.getInputStream()) {
            return new GeneratedFile(
                    stream.readAllBytes(),
                    "EXTN-QF-23_" + slug(survey.getTitle()) + ".docx",
                    DOCX_MIME);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to read the archived report.", ex);
        }
    }

    // --- helpers ---

    private Survey findActive(String id) {
        return surveyRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new NoSuchElementException("Survey not found."));
    }

    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        return userRepository.findByEmail(auth.getName()).map(User::getId).orElse(null);
    }

    private String toJson(Map<String, Object> scope) {
        try {
            return objectMapper.writeValueAsString(scope);
        } catch (Exception ex) {
            return null;
        }
    }

    /** Filesystem-safe form of a survey title, for the download filename. */
    private String slug(String title) {
        if (title == null || title.isBlank()) {
            return "survey";
        }
        String slug = title.trim().replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return slug.isEmpty() ? "survey" : slug;
    }
}
