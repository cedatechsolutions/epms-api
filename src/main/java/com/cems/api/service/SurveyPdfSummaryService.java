package com.cems.api.service;

import com.cems.api.dto.SurveyResultsResponse;
import com.cems.api.dto.SurveyResultsResponse.CategoryResult;
import com.cems.api.util.PdfDocumentBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * One-page PDF summary of a survey's ranked needs (spec Module 3 §4), with F/M columns throughout.
 * Uses the shared {@link PdfDocumentBuilder} — the project has no PDF library by design.
 */
@Service
public class SurveyPdfSummaryService {

    private static final double PAGE_WIDTH = 595;   // A4 portrait
    private static final double PAGE_HEIGHT = 842;
    private static final double MARGIN = 40;
    private static final double ROW_HEIGHT = 22;
    private static final double[] COLUMN_WIDTHS = {40, 150, 80, 80, 60, 55, 50};
    private static final String[] HEADERS =
            {"Rank", "Need Category", "Weighted Avg", "Priority", "Total", "Female", "Male"};
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final SurveyResultsService resultsService;

    public SurveyPdfSummaryService(SurveyResultsService resultsService) {
        this.resultsService = resultsService;
    }

    @Transactional(readOnly = true)
    public byte[] export(String surveyId) {
        SurveyResultsResponse results = resultsService.getResults(surveyId);

        PdfDocumentBuilder document = new PdfDocumentBuilder(PAGE_WIDTH, PAGE_HEIGHT);
        PdfDocumentBuilder.Page page = document.addPage();

        double cursor = PAGE_HEIGHT - MARGIN;

        page.text(PdfDocumentBuilder.FONT_BOLD, 16, MARGIN, cursor, "Needs Assessment Summary");
        cursor -= 20;
        page.text(PdfDocumentBuilder.FONT_REGULAR, 11, MARGIN, cursor,
                PdfDocumentBuilder.truncate(results.title(), 70));
        cursor -= 14;
        page.text(PdfDocumentBuilder.FONT_REGULAR, 9, MARGIN, cursor,
                results.communityName() + "  |  Status: " + results.status()
                        + "  |  " + (results.finalized() ? "Finalized" : "Not finalized"));
        cursor -= 12;
        page.text(PdfDocumentBuilder.FONT_REGULAR, 9, MARGIN, cursor,
                "Generated " + TIMESTAMP.format(Instant.now()));

        // Respondent totals — always sex-disaggregated (cross-cutting rule 1).
        cursor -= 28;
        page.text(PdfDocumentBuilder.FONT_BOLD, 11, MARGIN, cursor, "Respondents");
        cursor -= 16;
        page.text(PdfDocumentBuilder.FONT_REGULAR, 10, MARGIN, cursor,
                "Total: " + results.totalResponses()
                        + "     Female: " + results.femaleResponses()
                        + "     Male: " + results.maleResponses()
                        + "     Undisclosed: " + results.undisclosedSexResponses());
        if (results.completionRate() != null) {
            cursor -= 14;
            page.text(PdfDocumentBuilder.FONT_REGULAR, 10, MARGIN, cursor,
                    "Completion rate: " + results.completionRate() + "% of a "
                            + results.targetResponses() + "-response target");
        }

        cursor -= 30;
        page.text(PdfDocumentBuilder.FONT_BOLD, 11, MARGIN, cursor, "Ranked Needs");
        cursor -= 8;

        List<CategoryResult> categories = results.categories();
        if (categories.isEmpty()) {
            cursor -= 20;
            page.text(PdfDocumentBuilder.FONT_REGULAR, 10, MARGIN, cursor,
                    "No rating answers have been collected, so no needs could be ranked.");
            return document.build();
        }

        double tableWidth = Arrays.stream(COLUMN_WIDTHS).sum();
        double headerTop = cursor;
        page.fillRect(MARGIN, headerTop - ROW_HEIGHT, tableWidth, ROW_HEIGHT, "0.93 0.97 0.92 rg");
        page.strokeRect(MARGIN, headerTop - ROW_HEIGHT, tableWidth, ROW_HEIGHT, "0.72 0.81 0.70 RG");

        double x = MARGIN;
        for (int index = 0; index < HEADERS.length; index++) {
            page.text(PdfDocumentBuilder.FONT_BOLD, 8.5, x + 5, headerTop - 15, HEADERS[index]);
            x += COLUMN_WIDTHS[index];
        }

        for (int rowIndex = 0; rowIndex < categories.size(); rowIndex++) {
            CategoryResult category = categories.get(rowIndex);
            double rowTop = headerTop - ROW_HEIGHT - (rowIndex * ROW_HEIGHT);
            String fill = rowIndex % 2 == 0 ? "1 1 1 rg" : "0.98 0.99 0.97 rg";
            page.fillRect(MARGIN, rowTop - ROW_HEIGHT, tableWidth, ROW_HEIGHT, fill);
            page.strokeRect(MARGIN, rowTop - ROW_HEIGHT, tableWidth, ROW_HEIGHT, "0.88 0.92 0.86 RG");

            String[] values = {
                    String.valueOf(category.rank()),
                    PdfDocumentBuilder.truncate(category.needCategoryName(), 24),
                    category.avgScore().toPlainString(),
                    capitalize(category.priority()),
                    String.valueOf(category.responseCount()),
                    String.valueOf(category.femaleCount()),
                    String.valueOf(category.maleCount()),
            };

            double cellX = MARGIN;
            for (int index = 0; index < values.length; index++) {
                page.text(PdfDocumentBuilder.FONT_REGULAR, 9, cellX + 5, rowTop - 15, values[index]);
                cellX += COLUMN_WIDTHS[index];
            }
        }

        return document.build();
    }

    private String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
