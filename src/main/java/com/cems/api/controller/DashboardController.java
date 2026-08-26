package com.cems.api.controller;

import com.cems.api.dto.DashboardOverviewResponse;
import com.cems.api.dto.MonitoringDashboardResponse;
import com.cems.api.service.DashboardPdfExportService;
import com.cems.api.service.DashboardService;
import com.cems.api.service.DashboardXlsxExportService;
import com.cems.api.service.MonitoringDashboardService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Locale;

/**
 * The dashboard's two reads, which are deliberately separate endpoints with separate permissions:
 *
 * <ul>
 *   <li>{@code GET /api/dashboard/overview} — the personal landing summary every authenticated role
 *       gets. Proposal counts are scoped to the caller; the activity feed is attached only for
 *       administrators.</li>
 *   <li>{@code GET /api/dashboard} — the campus-wide M&amp;E dashboard (spec Module 6), restricted to
 *       coordinators and administrators. See {@code Permissions.canViewMonitoringDashboard()} for
 *       why faculty are excluded from this one.</li>
 * </ul>
 *
 * <p>Both are single-payload reads: no widget on either screen costs a second round trip.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final MonitoringDashboardService monitoringDashboardService;
    private final DashboardXlsxExportService xlsxExportService;
    private final DashboardPdfExportService pdfExportService;

    public DashboardController(DashboardService dashboardService,
            MonitoringDashboardService monitoringDashboardService,
            DashboardXlsxExportService xlsxExportService,
            DashboardPdfExportService pdfExportService) {
        this.dashboardService = dashboardService;
        this.monitoringDashboardService = monitoringDashboardService;
        this.xlsxExportService = xlsxExportService;
        this.pdfExportService = pdfExportService;
    }

    @PreAuthorize("@permissions.canViewDashboard()")
    @GetMapping("/overview")
    public ResponseEntity<DashboardOverviewResponse> overview() {
        return ResponseEntity.ok(dashboardService.getOverview());
    }

    /** @param periodId a seeded academic period, or omitted for every period at once. */
    @PreAuthorize("@permissions.canViewMonitoringDashboard()")
    @GetMapping
    public ResponseEntity<MonitoringDashboardResponse> monitoring(
            @RequestParam(name = "periodId", required = false) String periodId) {
        return ResponseEntity.ok(monitoringDashboardService.getDashboard(periodId));
    }

    /**
     * Snapshot of the same payload the screen renders (spec Module 6 §5). The dashboard is built
     * once here and handed to whichever renderer was asked for, which is what makes "exports match
     * on totals" true by construction rather than by two implementations agreeing.
     */
    @PreAuthorize("@permissions.canViewMonitoringDashboard()")
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(name = "periodId", required = false) String periodId,
            @RequestParam(name = "format", defaultValue = "xlsx") String format) {

        MonitoringDashboardResponse dashboard = monitoringDashboardService.getDashboard(periodId);
        String normalized = format.trim().toLowerCase(Locale.ROOT);

        byte[] content;
        String contentType;
        String extension;
        switch (normalized) {
            case "xlsx" -> {
                content = xlsxExportService.export(dashboard);
                contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                extension = "xlsx";
            }
            case "pdf" -> {
                content = pdfExportService.export(dashboard);
                contentType = "application/pdf";
                extension = "pdf";
            }
            default -> throw new IllegalArgumentException("Export format must be xlsx or pdf.");
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename(dashboard, extension) + "\"")
                .contentLength(content.length)
                .body(content);
    }

    /**
     * A filename that says what the file holds, because these land in shared drives beside a dozen
     * siblings: {@code cems-dashboard-S1-AY-2026-2027-2026-08-14.xlsx}.
     */
    private String filename(MonitoringDashboardResponse dashboard, String extension) {
        String period = dashboard.period() == null ? "all-periods" : dashboard.period().label();
        String slug = period.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return "cems-dashboard-" + slug + "-" + LocalDate.now() + "." + extension;
    }
}
