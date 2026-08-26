package com.cems.api.service;

import com.cems.api.dto.MonitoringDashboardResponse;
import com.cems.api.dto.MonitoringDashboardResponse.CompletionRow;
import com.cems.api.dto.MonitoringDashboardResponse.Kpis;
import com.cems.api.dto.MonitoringDashboardResponse.SectorCount;
import com.cems.api.dto.MonitoringDashboardResponse.TypeCount;
import com.cems.api.util.PdfDocumentBuilder;
import com.cems.api.util.PdfDocumentBuilder.Page;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * PDF snapshot of the M&amp;E dashboard (spec Module 6 §5), built on the shared
 * {@link PdfDocumentBuilder} — the project carries no PDF library.
 *
 * <p>Like the XLSX export it renders an already-built {@link MonitoringDashboardResponse} rather than
 * re-querying, so the snapshot, the workbook and the screen are the same numbers by construction.
 *
 * <p>This is a <em>snapshot</em>, not a report: it prints what the dashboard shows, including the
 * counting method, and leaves narrative and signatories to Module 7's accomplishment report.
 */
@Service
public class DashboardPdfExportService {

    private static final double PAGE_WIDTH = 595;   // A4 portrait
    private static final double PAGE_HEIGHT = 842;
    private static final double MARGIN = 36;
    private static final double CONTENT_WIDTH = PAGE_WIDTH - (MARGIN * 2);

    private static final double ROW_HEIGHT = 20;
    private static final int COMPLETION_ROWS_PER_PAGE = 26;
    private static final double[] COMPLETION_WIDTHS = {150, 95, 70, 48, 42, 38, 38, 42};
    private static final String[] COMPLETION_HEADERS =
            {"Program", "Community", "Status", "Target", "Total", "F", "M", "Post-eval"};

    /** Widest bar drawn for the chart blocks; every other bar is scaled against the largest value. */
    private static final double BAR_MAX_WIDTH = 260;
    private static final double BAR_HEIGHT = 9;

    private static final String INK = "0.11 0.13 0.11 rg";
    private static final String BAR_PRIMARY = "0.16 0.42 0.24 rg";
    private static final String BAR_MUTED = "0.62 0.72 0.63 rg";
    private static final String HEADER_FILL = "0.93 0.97 0.92 rg";
    private static final String HEADER_STROKE = "0.72 0.81 0.70 RG";
    private static final String ROW_STROKE = "0.88 0.92 0.86 RG";

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Manila"));

    public byte[] export(MonitoringDashboardResponse dashboard) {
        PdfDocumentBuilder document = new PdfDocumentBuilder(PAGE_WIDTH, PAGE_HEIGHT);

        Page first = document.addPage();
        double cursor = drawHeader(first, dashboard);
        cursor = drawKpis(first, dashboard.kpis(), cursor);
        cursor = drawSexSplit(first, dashboard.kpis(), cursor);
        cursor = drawTypeChart(first, dashboard.programsByType(), cursor);
        cursor = drawSectorChart(first, dashboard.beneficiariesBySector(), cursor);
        drawMethodNote(first, dashboard.kpis(), cursor);

        drawCompletionTable(document, dashboard);
        return document.build();
    }

    /** @return the y coordinate the next block starts from. */
    private double drawHeader(Page page, MonitoringDashboardResponse dashboard) {
        page.text(PdfDocumentBuilder.FONT_BOLD, 17, MARGIN, PAGE_HEIGHT - 48,
                "Monitoring & Evaluation Snapshot");
        page.text(PdfDocumentBuilder.FONT_REGULAR, 10, MARGIN, PAGE_HEIGHT - 66, periodLabel(dashboard));
        page.text(PdfDocumentBuilder.FONT_REGULAR, 8.5, MARGIN, PAGE_HEIGHT - 80,
                "Generated " + TIMESTAMP.format(dashboard.generatedAt()) + " (Asia/Manila)");
        return PAGE_HEIGHT - 104;
    }

    /** Four KPI boxes across one row, mirroring the card row at the top of the screen. */
    private double drawKpis(Page page, Kpis kpis, double top) {
        String[][] cards = {
                {"Communities served", String.valueOf(kpis.communitiesServed()), ""},
                {"Programs completed", kpis.programsCompleted() + " / " + kpis.programsTotal(), "of all programs"},
                {"Beneficiaries reached", String.valueOf(kpis.beneficiariesTotal()),
                        kpis.beneficiariesFemale() + " F / " + kpis.beneficiariesMale() + " M"},
                {"Faculty involved", String.valueOf(kpis.facultyInvolved()), ""},
        };

        double gap = 8;
        double cardWidth = (CONTENT_WIDTH - (gap * (cards.length - 1))) / cards.length;
        double cardHeight = 62;
        double x = MARGIN;
        for (String[] card : cards) {
            page.fillRect(x, top - cardHeight, cardWidth, cardHeight, HEADER_FILL);
            page.strokeRect(x, top - cardHeight, cardWidth, cardHeight, HEADER_STROKE);
            page.text(PdfDocumentBuilder.FONT_REGULAR, 7.5, x + 8, top - 16,
                    PdfDocumentBuilder.truncate(card[0], 22));
            page.text(PdfDocumentBuilder.FONT_BOLD, 17, x + 8, top - 38, card[1]);
            if (!card[2].isEmpty()) {
                page.text(PdfDocumentBuilder.FONT_REGULAR, 7.5, x + 8, top - 52,
                        PdfDocumentBuilder.truncate(card[2], 24));
            }
            x += cardWidth + gap;
        }
        return top - cardHeight - 26;
    }

    /** The GAD split as one stacked bar — the figure this whole module exists to report. */
    private double drawSexSplit(Page page, Kpis kpis, double top) {
        page.text(PdfDocumentBuilder.FONT_BOLD, 10, MARGIN, top, "Beneficiaries by sex");
        double barTop = top - 12;
        long total = kpis.beneficiariesTotal();

        if (total == 0) {
            page.text(PdfDocumentBuilder.FONT_REGULAR, 8.5, MARGIN, barTop - 8,
                    "No attendance has been recorded in this period.");
            return barTop - 28;
        }

        double femaleWidth = CONTENT_WIDTH * (kpis.beneficiariesFemale() / (double) total);
        page.fillRect(MARGIN, barTop - BAR_HEIGHT, femaleWidth, BAR_HEIGHT, BAR_PRIMARY);
        page.fillRect(MARGIN + femaleWidth, barTop - BAR_HEIGHT, CONTENT_WIDTH - femaleWidth,
                BAR_HEIGHT, BAR_MUTED);
        page.text(PdfDocumentBuilder.FONT_REGULAR, 8.5, MARGIN, barTop - BAR_HEIGHT - 12,
                "Female " + kpis.beneficiariesFemale() + "   ·   Male " + kpis.beneficiariesMale()
                        + "   ·   Total " + total);
        return barTop - BAR_HEIGHT - 32;
    }

    private double drawTypeChart(Page page, List<TypeCount> types, double top) {
        page.text(PdfDocumentBuilder.FONT_BOLD, 10, MARGIN, top, "Programs by type");
        if (types.isEmpty()) {
            page.text(PdfDocumentBuilder.FONT_REGULAR, 8.5, MARGIN, top - 14,
                    "No programs with a type in this period.");
            return top - 34;
        }
        long max = types.stream().mapToLong(TypeCount::programs).max().orElse(1);
        double y = top - 16;
        for (TypeCount type : types) {
            page.text(PdfDocumentBuilder.FONT_REGULAR, 8, MARGIN, y,
                    PdfDocumentBuilder.truncate(type.programTypeName(), 34));
            page.fillRect(MARGIN + 190, y - 1, scaled(type.programs(), max), BAR_HEIGHT - 2, BAR_PRIMARY);
            page.text(PdfDocumentBuilder.FONT_REGULAR, 8, MARGIN + 190 + BAR_MAX_WIDTH + 8, y,
                    String.valueOf(type.programs()));
            y -= 15;
        }
        return y - 14;
    }

    private double drawSectorChart(Page page, List<SectorCount> sectors, double top) {
        page.text(PdfDocumentBuilder.FONT_BOLD, 10, MARGIN, top, "Beneficiaries by sector (Total / F / M)");
        if (sectors.isEmpty()) {
            page.text(PdfDocumentBuilder.FONT_REGULAR, 8.5, MARGIN, top - 14,
                    "No attendance has been recorded in this period.");
            return top - 34;
        }
        long max = sectors.stream().mapToLong(SectorCount::total).max().orElse(1);
        double y = top - 16;
        for (SectorCount sector : sectors) {
            page.text(PdfDocumentBuilder.FONT_REGULAR, 8, MARGIN, y,
                    PdfDocumentBuilder.truncate(sector.sectorName(), 34));
            // Female segment first, then male, so the split is legible without a legend per bar.
            double femaleWidth = sector.total() == 0
                    ? 0
                    : scaled(sector.total(), max) * (sector.female() / (double) sector.total());
            page.fillRect(MARGIN + 190, y - 1, femaleWidth, BAR_HEIGHT - 2, BAR_PRIMARY);
            page.fillRect(MARGIN + 190 + femaleWidth, y - 1,
                    scaled(sector.total(), max) - femaleWidth, BAR_HEIGHT - 2, BAR_MUTED);
            page.text(PdfDocumentBuilder.FONT_REGULAR, 8, MARGIN + 190 + BAR_MAX_WIDTH + 8, y,
                    sector.total() + " (" + sector.female() + "/" + sector.male() + ")");
            y -= 15;
        }
        return y - 14;
    }

    /** The counting method, printed on the snapshot itself — see MonitoringDashboardService. */
    private void drawMethodNote(Page page, Kpis kpis, double top) {
        page.text(PdfDocumentBuilder.FONT_BOLD, 8, MARGIN, top, "How beneficiaries are counted");
        double y = top - 12;
        for (String line : wrap(kpis.beneficiaryMethod(), 108)) {
            page.text(PdfDocumentBuilder.FONT_REGULAR, 7.5, MARGIN, y, line);
            y -= 10;
        }
    }

    /** The completion table gets its own pages so a long semester never truncates mid-row. */
    private void drawCompletionTable(PdfDocumentBuilder document, MonitoringDashboardResponse dashboard) {
        List<CompletionRow> rows = dashboard.completion();
        int pageCount = Math.max(1, (int) Math.ceil(rows.size() / (double) COMPLETION_ROWS_PER_PAGE));

        for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
            Page page = document.addPage();
            page.text(PdfDocumentBuilder.FONT_BOLD, 13, MARGIN, PAGE_HEIGHT - 46, "Program completion");
            page.text(PdfDocumentBuilder.FONT_REGULAR, 8.5, MARGIN, PAGE_HEIGHT - 62,
                    periodLabel(dashboard) + "   ·   " + describeCoverage(dashboard)
                            + "   ·   page " + (pageIndex + 1) + " of " + pageCount);

            double tableTop = PAGE_HEIGHT - 84;
            drawCompletionHeader(page, tableTop);

            if (rows.isEmpty()) {
                page.text(PdfDocumentBuilder.FONT_REGULAR, 9, MARGIN + 8, tableTop - ROW_HEIGHT - 18,
                        "No programs fall in this period.");
                continue;
            }

            int start = pageIndex * COMPLETION_ROWS_PER_PAGE;
            int end = Math.min(start + COMPLETION_ROWS_PER_PAGE, rows.size());
            for (int index = start; index < end; index++) {
                drawCompletionRow(page, rows.get(index), index - start, tableTop);
            }
        }
    }

    private void drawCompletionHeader(Page page, double tableTop) {
        double width = totalWidth();
        page.fillRect(MARGIN, tableTop - ROW_HEIGHT, width, ROW_HEIGHT, HEADER_FILL);
        page.strokeRect(MARGIN, tableTop - ROW_HEIGHT, width, ROW_HEIGHT, HEADER_STROKE);
        double x = MARGIN;
        for (int index = 0; index < COMPLETION_HEADERS.length; index++) {
            page.text(PdfDocumentBuilder.FONT_BOLD, 7.5, x + 5, tableTop - 13, COMPLETION_HEADERS[index]);
            x += COMPLETION_WIDTHS[index];
        }
    }

    private void drawCompletionRow(Page page, CompletionRow completion, int rowIndex, double tableTop) {
        double width = totalWidth();
        double rowTop = tableTop - ROW_HEIGHT - (rowIndex * ROW_HEIGHT);
        page.fillRect(MARGIN, rowTop - ROW_HEIGHT, width, ROW_HEIGHT,
                rowIndex % 2 == 0 ? "1 1 1 rg" : "0.98 0.99 0.97 rg");
        page.strokeRect(MARGIN, rowTop - ROW_HEIGHT, width, ROW_HEIGHT, ROW_STROKE);

        String[] values = {
                PdfDocumentBuilder.truncate(completion.title(), 36),
                PdfDocumentBuilder.truncate(nullToDash(completion.communityName()), 22),
                PdfDocumentBuilder.truncate(completion.status().replace('_', ' '), 16),
                // An unset target prints as a dash. Printing 0 would assert a target nobody set.
                completion.targetBeneficiaries() == null ? "-" : String.valueOf(completion.targetBeneficiaries()),
                String.valueOf(completion.actualTotal()),
                String.valueOf(completion.actualFemale()),
                String.valueOf(completion.actualMale()),
                completion.hasPostEvaluation() ? "Yes" : "No",
        };

        double x = MARGIN;
        for (int index = 0; index < values.length; index++) {
            page.text(PdfDocumentBuilder.FONT_REGULAR, 7.5, x + 5, rowTop - 13, values[index]);
            x += COMPLETION_WIDTHS[index];
        }
    }

    private String describeCoverage(MonitoringDashboardResponse dashboard) {
        int shown = dashboard.completion().size();
        long total = dashboard.completionTotal();
        return shown >= total
                ? total + " program" + (total == 1 ? "" : "s")
                : "showing " + shown + " of " + total + " programs";
    }

    private double scaled(long value, long max) {
        return max <= 0 ? 0 : BAR_MAX_WIDTH * (value / (double) max);
    }

    private double totalWidth() {
        double width = 0;
        for (double column : COMPLETION_WIDTHS) {
            width += column;
        }
        return width;
    }

    private String periodLabel(MonitoringDashboardResponse dashboard) {
        return dashboard.period() == null ? "All academic periods" : dashboard.period().label();
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /** Greedy word wrap — the PDF builder draws single lines, so long prose is split here. */
    private List<String> wrap(String text, int maxChars) {
        List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > maxChars) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }
}
