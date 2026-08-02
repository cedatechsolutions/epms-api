package com.cems.api.service;

import com.cems.api.dto.SurveyResultsResponse;
import com.cems.api.dto.SurveyResultsResponse.CategoryResult;
import com.cems.api.entity.SurveyAnswer;
import com.cems.api.entity.SurveyQuestion;
import com.cems.api.entity.SurveyResponse;
import com.cems.api.repository.SurveyAnswerRepository;
import com.cems.api.repository.SurveyQuestionRepository;
import com.cems.api.repository.SurveyResponseRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * XLSX export of a survey's results (spec Module 3 §4): a raw-responses sheet and a
 * sex-disaggregated summary sheet. Built with Apache POI's streaming workbook (SXSSF) so a survey
 * with thousands of responses does not have to fit in memory.
 */
@Service
public class SurveyXlsxExportService {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final SurveyQuestionRepository questionRepository;
    private final SurveyResponseRepository responseRepository;
    private final SurveyAnswerRepository answerRepository;
    private final SurveyResultsService resultsService;

    public SurveyXlsxExportService(SurveyQuestionRepository questionRepository,
            SurveyResponseRepository responseRepository,
            SurveyAnswerRepository answerRepository,
            SurveyResultsService resultsService) {
        this.questionRepository = questionRepository;
        this.responseRepository = responseRepository;
        this.answerRepository = answerRepository;
        this.resultsService = resultsService;
    }

    @Transactional(readOnly = true)
    public byte[] export(String surveyId) {
        SurveyResultsResponse results = resultsService.getResults(surveyId);
        List<SurveyQuestion> questions = questionRepository.findBySurveyIdOrderByOrderIndexAsc(surveyId);
        List<SurveyResponse> responses = responseRepository.findBySurveyId(surveyId);
        List<SurveyAnswer> answers = answerRepository.findAllBySurveyId(surveyId);

        try (SXSSFWorkbook workbook = new SXSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            CellStyle header = headerStyle(workbook);
            writeSummarySheet(workbook, header, results);
            writeRawSheet(workbook, header, questions, responses, answers);

            workbook.write(output);
            workbook.dispose();
            return output.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to generate the XLSX export.", ex);
        }
    }

    /** Sheet 1 — ranked needs with GAD disaggregation (Total / Female / Male per category). */
    private void writeSummarySheet(SXSSFWorkbook workbook, CellStyle headerStyle, SurveyResultsResponse results) {
        Sheet sheet = workbook.createSheet("Summary (Sex-Disaggregated)");
        int rowIndex = 0;

        rowIndex = writeRow(sheet, rowIndex, headerStyle, "Survey", results.title());
        rowIndex = writeRow(sheet, rowIndex, null, "Community", results.communityName());
        rowIndex = writeRow(sheet, rowIndex, null, "Status", results.status());
        rowIndex = writeRow(sheet, rowIndex, null, "Results finalized", results.finalized() ? "Yes" : "No");
        rowIndex = writeRow(sheet, rowIndex, null, "Total respondents", String.valueOf(results.totalResponses()));
        rowIndex = writeRow(sheet, rowIndex, null, "Female respondents", String.valueOf(results.femaleResponses()));
        rowIndex = writeRow(sheet, rowIndex, null, "Male respondents", String.valueOf(results.maleResponses()));
        rowIndex = writeRow(sheet, rowIndex, null, "Undisclosed sex",
                String.valueOf(results.undisclosedSexResponses()));
        if (results.completionRate() != null) {
            rowIndex = writeRow(sheet, rowIndex, null, "Completion rate", results.completionRate() + "%");
        }
        rowIndex++;

        Row head = sheet.createRow(rowIndex++);
        String[] columns = {"Rank", "Need Category", "Weighted Average", "Priority",
                "Respondents (Total)", "Female", "Male"};
        for (int index = 0; index < columns.length; index++) {
            Cell cell = head.createCell(index);
            cell.setCellValue(columns[index]);
            cell.setCellStyle(headerStyle);
        }

        for (CategoryResult category : results.categories()) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(category.rank());
            row.createCell(1).setCellValue(category.needCategoryName());
            row.createCell(2).setCellValue(category.avgScore().doubleValue());
            row.createCell(3).setCellValue(category.priority());
            row.createCell(4).setCellValue(category.responseCount());
            row.createCell(5).setCellValue(category.femaleCount());
            row.createCell(6).setCellValue(category.maleCount());
        }
    }

    /** Sheet 2 — one row per response: demographics then one column per question. No PII. */
    private void writeRawSheet(SXSSFWorkbook workbook,
            CellStyle headerStyle,
            List<SurveyQuestion> questions,
            List<SurveyResponse> responses,
            List<SurveyAnswer> answers) {
        Sheet sheet = workbook.createSheet("Raw Responses");

        Row head = sheet.createRow(0);
        List<String> columns = new ArrayList<>(List.of("Respondent #", "Sex", "Age Group", "Sector", "Submitted"));
        questions.forEach(question -> columns.add(question.getQuestionText()));
        for (int index = 0; index < columns.size(); index++) {
            Cell cell = head.createCell(index);
            cell.setCellValue(columns.get(index));
            cell.setCellStyle(headerStyle);
        }

        // answers keyed by responseId -> questionId, so each row is a simple lookup.
        Map<String, Map<String, String>> byResponse = new HashMap<>();
        for (SurveyAnswer answer : answers) {
            if (answer.getSurveyResponse() == null || answer.getSurveyQuestion() == null) {
                continue;
            }
            byResponse
                    .computeIfAbsent(answer.getSurveyResponse().getId(), ignored -> new HashMap<>())
                    .put(answer.getSurveyQuestion().getId(), answer.getAnswerValue());
        }

        int rowIndex = 1;
        int respondentNumber = 1;
        for (SurveyResponse response : responses) {
            Row row = sheet.createRow(rowIndex++);
            // Respondents are numbered, never named — the survey collects no identifying data.
            row.createCell(0).setCellValue(respondentNumber++);
            row.createCell(1).setCellValue(response.getRespondentSex());
            row.createCell(2).setCellValue(response.getRespondentAgeGroup() == null
                    ? "" : response.getRespondentAgeGroup());
            row.createCell(3).setCellValue(response.getRespondentSector() == null
                    ? "" : response.getRespondentSector().getName());
            row.createCell(4).setCellValue(response.getSubmittedAt() == null
                    ? "" : TIMESTAMP.format(response.getSubmittedAt()));

            Map<String, String> answersForResponse = byResponse.getOrDefault(response.getId(), Map.of());
            for (int index = 0; index < questions.size(); index++) {
                String value = answersForResponse.get(questions.get(index).getId());
                row.createCell(5 + index).setCellValue(value == null ? "" : value);
            }
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

    private CellStyle headerStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }
}
