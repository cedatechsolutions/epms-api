package com.cems.api.service;

import com.cems.api.dto.MonitoringDashboardResponse;
import com.cems.api.dto.MonitoringDashboardResponse.CompletionRow;
import com.cems.api.dto.MonitoringDashboardResponse.Kpis;
import com.cems.api.dto.MonitoringDashboardResponse.SectorCount;
import com.cems.api.dto.MonitoringDashboardResponse.TypeCount;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * XLSX export of the M&amp;E dashboard (spec Module 6 §5: "underlying data as Excel, one sheet per
 * widget, all person counts sex-disaggregated").
 *
 * <p>Takes the already-built {@link MonitoringDashboardResponse} rather than re-querying, which is
 * what guarantees the acceptance criterion "exports match on totals": the workbook and the screen
 * are two renderings of one payload, so they cannot drift even if a program is approved mid-export.
 */
@Service
public class DashboardXlsxExportService {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Manila"));

    public byte[] export(MonitoringDashboardResponse dashboard) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            CellStyle header = headerStyle(workbook);
            writeKpiSheet(workbook, header, dashboard);
            writeProgramsByTypeSheet(workbook, header, dashboard);
            writeBeneficiariesBySectorSheet(workbook, header, dashboard);
            writeBeneficiariesBySexSheet(workbook, header, dashboard);
            writeCompletionSheet(workbook, header, dashboard);

            workbook.write(output);
            workbook.dispose();
            return output.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to generate the dashboard XLSX export.", ex);
        }
    }

    /** Sheet 1 — the four KPI cards, plus the provenance a printed figure needs to be defensible. */
    private void writeKpiSheet(SXSSFWorkbook workbook, CellStyle headerStyle,
            MonitoringDashboardResponse dashboard) {
        Sheet sheet = workbook.createSheet("KPIs");
        Kpis kpis = dashboard.kpis();
        int rowIndex = 0;

        rowIndex = writeRow(sheet, rowIndex, headerStyle, "CEMS Monitoring & Evaluation Dashboard", "");
        rowIndex = writeRow(sheet, rowIndex, null, "Academic period", periodLabel(dashboard));
        rowIndex = writeRow(sheet, rowIndex, null, "Generated", TIMESTAMP.format(dashboard.generatedAt()));
        rowIndex++;

        rowIndex = writeRow(sheet, rowIndex, headerStyle, "Indicator", "Value");
        rowIndex = writeRow(sheet, rowIndex, null, "Communities served", kpis.communitiesServed());
        rowIndex = writeRow(sheet, rowIndex, null, "Programs (total)", kpis.programsTotal());
        rowIndex = writeRow(sheet, rowIndex, null, "Programs completed", kpis.programsCompleted());
        rowIndex = writeRow(sheet, rowIndex, null, "Beneficiaries reached (total)", kpis.beneficiariesTotal());
        rowIndex = writeRow(sheet, rowIndex, null, "Beneficiaries reached (female)", kpis.beneficiariesFemale());
        rowIndex = writeRow(sheet, rowIndex, null, "Beneficiaries reached (male)", kpis.beneficiariesMale());
        rowIndex = writeRow(sheet, rowIndex, null, "Faculty involved", kpis.facultyInvolved());
        rowIndex++;

        // The counting method travels with the workbook. A beneficiary total detached from how it
        // was counted is exactly the figure that gets misquoted in an accomplishment report.
        rowIndex = writeRow(sheet, rowIndex, headerStyle, "How beneficiaries are counted", "");
        writeRow(sheet, rowIndex, null, kpis.beneficiaryMethod(), "");
    }

    /** Sheet 2 — the programs-by-type bar chart, as rows. */
    private void writeProgramsByTypeSheet(SXSSFWorkbook workbook, CellStyle headerStyle,
            MonitoringDashboardResponse dashboard) {
        Sheet sheet = workbook.createSheet("Programs by Type");
        writeHeaderRow(sheet, headerStyle, 0, "Program Type", "Programs");
        int rowIndex = 1;
        for (TypeCount type : dashboard.programsByType()) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(type.programTypeName());
            row.createCell(1).setCellValue(type.programs());
        }
    }

    /** Sheet 3 — beneficiaries by sector, disaggregated (cross-cutting rule 1). */
    private void writeBeneficiariesBySectorSheet(SXSSFWorkbook workbook, CellStyle headerStyle,
            MonitoringDashboardResponse dashboard) {
        Sheet sheet = workbook.createSheet("Beneficiaries by Sector");
        writeHeaderRow(sheet, headerStyle, 0, "Sector", "Total", "Female", "Male");
        int rowIndex = 1;
        for (SectorCount sector : dashboard.beneficiariesBySector()) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(sector.sectorName());
            row.createCell(1).setCellValue(sector.total());
            row.createCell(2).setCellValue(sector.female());
            row.createCell(3).setCellValue(sector.male());
        }
    }

    /** Sheet 4 — the GAD split on its own sheet, because this is the one GAD reporting asks for. */
    private void writeBeneficiariesBySexSheet(SXSSFWorkbook workbook, CellStyle headerStyle,
            MonitoringDashboardResponse dashboard) {
        Sheet sheet = workbook.createSheet("Beneficiaries by Sex");
        Kpis kpis = dashboard.kpis();
        writeHeaderRow(sheet, headerStyle, 0, "Sex", "Beneficiaries");
        Row female = sheet.createRow(1);
        female.createCell(0).setCellValue("Female");
        female.createCell(1).setCellValue(kpis.beneficiariesFemale());
        Row male = sheet.createRow(2);
        male.createCell(0).setCellValue("Male");
        male.createCell(1).setCellValue(kpis.beneficiariesMale());
        Row total = sheet.createRow(3);
        total.createCell(0).setCellValue("Total");
        total.createCell(1).setCellValue(kpis.beneficiariesTotal());
    }

    /** Sheet 5 — the program completion table (spec Module 6 §4). */
    private void writeCompletionSheet(SXSSFWorkbook workbook, CellStyle headerStyle,
            MonitoringDashboardResponse dashboard) {
        Sheet sheet = workbook.createSheet("Program Completion");
        writeHeaderRow(sheet, headerStyle, 0,
                "Program", "Community", "Type", "Status", "Target Beneficiaries",
                "Actual (Total)", "Actual (Female)", "Actual (Male)", "Pre-Evaluation", "Post-Evaluation");

        int rowIndex = 1;
        for (CompletionRow completion : dashboard.completion()) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(completion.title());
            row.createCell(1).setCellValue(nullToDash(completion.communityName()));
            row.createCell(2).setCellValue(nullToDash(completion.programTypeName()));
            row.createCell(3).setCellValue(completion.status());
            // A blank target is left blank rather than written as 0: no target set and a target of
            // zero are different facts, and a spreadsheet reader cannot tell them apart afterwards.
            if (completion.targetBeneficiaries() == null) {
                row.createCell(4).setCellValue("");
            } else {
                row.createCell(4).setCellValue(completion.targetBeneficiaries());
            }
            row.createCell(5).setCellValue(completion.actualTotal());
            row.createCell(6).setCellValue(completion.actualFemale());
            row.createCell(7).setCellValue(completion.actualMale());
            row.createCell(8).setCellValue(completion.hasPreEvaluation() ? "Yes" : "No");
            row.createCell(9).setCellValue(completion.hasPostEvaluation() ? "Yes" : "No");
        }

        if (dashboard.completionTotal() > dashboard.completion().size()) {
            Row note = sheet.createRow(rowIndex + 1);
            note.createCell(0).setCellValue("Showing " + dashboard.completion().size() + " of "
                    + dashboard.completionTotal() + " programs. Narrow the academic period for the rest.");
        }
    }

    private String periodLabel(MonitoringDashboardResponse dashboard) {
        return dashboard.period() == null
                ? "All periods"
                : dashboard.period().label() + " (" + dashboard.period().startsOn()
                        + " to " + dashboard.period().endsOn() + ")";
    }

    private void writeHeaderRow(Sheet sheet, CellStyle style, int rowIndex, String... columns) {
        Row row = sheet.createRow(rowIndex);
        for (int index = 0; index < columns.length; index++) {
            Cell cell = row.createCell(index);
            cell.setCellValue(columns[index]);
            cell.setCellStyle(style);
        }
    }

    private int writeRow(Sheet sheet, int rowIndex, CellStyle style, String label, String value) {
        Row row = sheet.createRow(rowIndex);
        Cell labelCell = row.createCell(0);
        labelCell.setCellValue(label);
        if (style != null) {
            labelCell.setCellStyle(style);
        }
        row.createCell(1).setCellValue(value);
        return rowIndex + 1;
    }

    private int writeRow(Sheet sheet, int rowIndex, CellStyle style, String label, long value) {
        Row row = sheet.createRow(rowIndex);
        Cell labelCell = row.createCell(0);
        labelCell.setCellValue(label);
        if (style != null) {
            labelCell.setCellStyle(style);
        }
        row.createCell(1).setCellValue(value);
        return rowIndex + 1;
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private CellStyle headerStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }
}
