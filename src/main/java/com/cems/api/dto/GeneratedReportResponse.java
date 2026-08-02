package com.cems.api.dto;

import com.cems.api.entity.GeneratedReport;

import java.time.Instant;

/** An archived generated document (spec §3.6). The storage path is never exposed to clients. */
public record GeneratedReportResponse(
        String id,
        String reportType,
        String fileType,
        Instant createdAt) {

    public static GeneratedReportResponse fromEntity(GeneratedReport report) {
        return new GeneratedReportResponse(
                report.getId(),
                report.getReportType(),
                report.getFileType(),
                report.getCreatedAt());
    }
}
