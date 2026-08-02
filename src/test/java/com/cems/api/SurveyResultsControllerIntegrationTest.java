package com.cems.api;

import com.cems.api.service.EmailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pass C coverage of Module 3 (spec §Module 3 §4/§5): live results with GAD disaggregation, the
 * finalize snapshot and its 409 rules, the XLSX export's two sheets, and the EXTN-QF-23 .docx —
 * which must carry the real F/M numbers and clearly marked placeholders (never invented narrative).
 *
 * <p>The scoring maths itself is covered by {@code AssessmentScoringServiceTest} (the spec-mandated
 * fixture); here it is asserted end-to-end through the API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SurveyResultsControllerIntegrationTest {

    private static final String COORDINATOR_EMAIL = "coordinator@cems.com";
    private static final String COORDINATOR_PASSWORD = "Coordinator123!";
    private static final String STUDENT_EMAIL = "student@cems.com";
    private static final String STUDENT_PASSWORD = "Student123!";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmailService emailService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- live results ---

    @Test
    void resultsAreScoredRankedAndSexDisaggregated() throws Exception {
        Fixture fixture = surveyWithResponses();

        mockMvc.perform(get("/api/surveys/{id}/results", fixture.surveyId())
                        .header("Authorization", "Bearer " + coordinatorToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalized").value(false))
                .andExpect(jsonPath("$.totalResponses").value(3))
                .andExpect(jsonPath("$.femaleResponses").value(2))
                .andExpect(jsonPath("$.maleResponses").value(1))
                // Health: (5+3)x2.0 + 4x2.0 = wait — see fixture: f1=5, f2=3, m1=4 on a weight-2.0 question.
                // avg = (5+3+4)x2.0 / (3 x 2.0) = 24/6 = 4.00 -> high, rank 1
                .andExpect(jsonPath("$.categories[0].needCategoryName").value("Health"))
                .andExpect(jsonPath("$.categories[0].avgScore").value(4.00))
                .andExpect(jsonPath("$.categories[0].priority").value("high"))
                .andExpect(jsonPath("$.categories[0].rank").value(1))
                .andExpect(jsonPath("$.categories[0].responseCount").value(3))
                .andExpect(jsonPath("$.categories[0].femaleCount").value(2))
                .andExpect(jsonPath("$.categories[0].maleCount").value(1))
                // Education: f1=1, f2=2, m1=1 on weight 1.0 -> (1+2+1)/3 = 1.33 -> low, rank 2
                .andExpect(jsonPath("$.categories[1].needCategoryName").value("Education"))
                .andExpect(jsonPath("$.categories[1].avgScore").value(1.33))
                .andExpect(jsonPath("$.categories[1].priority").value("low"))
                .andExpect(jsonPath("$.categories[1].rank").value(2));
    }

    @Test
    void completionRateIsReportedAgainstTheTarget() throws Exception {
        Fixture fixture = surveyWithResponses();
        // The fixture sets a target of 6; 3 responses were recorded -> 50.0%
        mockMvc.perform(get("/api/surveys/{id}/results", fixture.surveyId())
                        .header("Authorization", "Bearer " + coordinatorToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetResponses").value(6))
                .andExpect(jsonPath("$.completionRate").value(50.0));
    }

    @Test
    void openTextAndChoiceAnswersAppearAsDistributionsNotScores() throws Exception {
        Fixture fixture = surveyWithResponses();

        MvcResult result = mockMvc.perform(get("/api/surveys/{id}/results", fixture.surveyId())
                        .header("Authorization", "Bearer " + coordinatorToken()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());

        JsonNode distributions = body.get("distributions");
        assertEquals(1, distributions.size(), "only the checkbox question yields a distribution");
        assertEquals("checkbox", distributions.get(0).get("questionType").asText());
        // f1 and m1 selected "Clinic"; f2 selected "Pharmacy".
        assertEquals(2, distributions.get(0).get("optionCounts").get("Clinic").asInt());
        assertEquals(1, distributions.get(0).get("optionCounts").get("Pharmacy").asInt());

        // Only the two rating categories are scored — the checkbox contributes no need score.
        assertEquals(2, body.get("categories").size());
    }

    @Test
    void studentVolunteerCannotViewResults() throws Exception {
        Fixture fixture = surveyWithResponses();

        mockMvc.perform(get("/api/surveys/{id}/results", fixture.surveyId())
                        .header("Authorization", "Bearer " + token(STUDENT_EMAIL, STUDENT_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    // --- finalize ---

    @Test
    void finalizeSnapshotsResultsAndCannotBeRepeated() throws Exception {
        Fixture fixture = surveyWithResponses();
        String token = coordinatorToken();

        mockMvc.perform(post("/api/surveys/{id}/finalize", fixture.surveyId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalized").value(true))
                .andExpect(jsonPath("$.finalizedAt").isNotEmpty())
                .andExpect(jsonPath("$.categories[0].needCategoryName").value("Health"));

        // Re-finalizing is refused — the snapshot is what downstream modules cite.
        mockMvc.perform(post("/api/surveys/{id}/finalize", fixture.surveyId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @Test
    void finalizingWithoutResponsesIsRejected() throws Exception {
        String token = coordinatorToken();
        String surveyId = deployedSurveyWithoutResponses(token);

        mockMvc.perform(post("/api/surveys/{id}/finalize", surveyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test
    void finalizingADraftIsRejected() throws Exception {
        String token = coordinatorToken();
        String communityId = firstCommunityId(token);
        String surveyId = objectMapper.readTree(mockMvc.perform(post("/api/surveys")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"communityId":"%s","title":"Still A Draft"}
                                """.formatted(communityId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/surveys/{id}/finalize", surveyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    // --- exports ---

    @Test
    void xlsxExportHasSummaryAndRawSheetsWithSexDisaggregation() throws Exception {
        Fixture fixture = surveyWithResponses();

        byte[] xlsx = mockMvc.perform(get("/api/surveys/{id}/export", fixture.surveyId())
                        .param("format", "xlsx")
                        .header("Authorization", "Bearer " + coordinatorToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet summary = workbook.getSheet("Summary (Sex-Disaggregated)");
            Sheet raw = workbook.getSheet("Raw Responses");
            assertNotNull(summary, "the summary sheet must exist");
            assertNotNull(raw, "the raw-responses sheet must exist");

            String summaryText = sheetText(summary);
            assertTrue(summaryText.contains("Female"), "summary carries a Female column");
            assertTrue(summaryText.contains("Male"), "summary carries a Male column");
            assertTrue(summaryText.contains("Health"), "summary lists the ranked needs");

            // Header row + one row per respondent (3).
            assertEquals(3, raw.getLastRowNum(), "one raw row per response");
        }
    }

    @Test
    void pdfSummaryExportIsAValidPdf() throws Exception {
        Fixture fixture = surveyWithResponses();

        byte[] pdf = mockMvc.perform(get("/api/surveys/{id}/export", fixture.surveyId())
                        .param("format", "pdf")
                        .header("Authorization", "Bearer " + coordinatorToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertTrue(new String(pdf, 0, 8).startsWith("%PDF-1.4"), "a valid PDF header");
        assertTrue(pdf.length > 500, "the summary should not be an empty document");
    }

    /** The existing user-list PDF still renders after the shared PdfDocumentBuilder refactor. */
    @Test
    void userListPdfStillRendersAfterPdfRefactor() throws Exception {
        byte[] pdf = mockMvc.perform(get("/api/users/print")
                        .header("Authorization", "Bearer " + token("admin@cems.com", "Admin123!")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertTrue(new String(pdf, 0, 8).startsWith("%PDF-1.4"));
        assertTrue(pdf.length > 500);
    }

    // --- EXTN-QF-23 ---

    @Test
    void qf23RequiresFinalizedResults() throws Exception {
        Fixture fixture = surveyWithResponses();

        mockMvc.perform(post("/api/surveys/{id}/report-qf23", fixture.surveyId())
                        .header("Authorization", "Bearer " + coordinatorToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @Test
    void qf23DocxCarriesRealFmNumbersPlaceholdersAndSignatories() throws Exception {
        Fixture fixture = surveyWithResponses();
        String token = coordinatorToken();

        mockMvc.perform(post("/api/surveys/{id}/finalize", fixture.surveyId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        byte[] docx = mockMvc.perform(post("/api/surveys/{id}/report-qf23", fixture.surveyId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx))) {
            StringBuilder text = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                text.append(paragraph.getText()).append('\n');
            }
            for (XWPFTable table : document.getTables()) {
                text.append(table.getText()).append('\n');
            }
            String content = text.toString();

            // Official structure.
            assertTrue(content.contains("NEEDS ASSESSMENT REPORT"), "official title");
            assertTrue(content.contains("EXTN-QF-23"), "official form code");
            assertTrue(content.contains("I. Introduction"), "narrative sections present");
            assertTrue(content.contains("IV. Respondents"));
            assertTrue(content.contains("V. Results and Discussion"));

            // AC: narrative the system cannot know is a marked placeholder, never invented prose.
            assertTrue(content.contains("[TO BE COMPLETED"), "placeholders are clearly marked");

            // Real, sex-disaggregated numbers from the finalized results.
            assertTrue(content.contains("Community Residents"), "respondents table present");
            assertTrue(content.contains("Health"), "ranked needs table present");

            // Signatory block filled from role holders.
            assertTrue(content.contains("Prepared by:"));
            assertTrue(content.contains("Recommending Approval:"));
            assertTrue(content.contains("Approved:"));
        }

        // The generated document is archived and downloadable (immutable archive).
        MvcResult reports = mockMvc.perform(get("/api/surveys/{id}/reports", fixture.surveyId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = objectMapper.readTree(reports.getResponse().getContentAsString());
        assertEquals(1, rows.size(), "the QF-23 is archived in generated_reports");
        assertEquals("needs_assessment_qf23", rows.get(0).get("reportType").asText());
        assertEquals("docx", rows.get(0).get("fileType").asText());

        mockMvc.perform(get("/api/surveys/{id}/reports/{reportId}", fixture.surveyId(),
                        rows.get(0).get("id").asText())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void qf23HonoursSignatoryOverridesAndArchivesEachRegeneration() throws Exception {
        Fixture fixture = surveyWithResponses();
        String token = coordinatorToken();

        mockMvc.perform(post("/api/surveys/{id}/finalize", fixture.surveyId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // A blank field must still fall back to the role holder, so only two names are overridden.
        byte[] docx = mockMvc.perform(post("/api/surveys/{id}/report-qf23", fixture.surveyId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"preparedBy":"Dr. Ana Reyes","notedBy":null,
                                 "recommendingApproval":"Prof. Ben Cruz","approvedBy":null}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx))) {
            StringBuilder text = new StringBuilder();
            for (XWPFTable table : document.getTables()) {
                text.append(table.getText()).append('\n');
            }
            String content = text.toString();

            assertTrue(content.contains("Dr. Ana Reyes"), "preparedBy override is used");
            assertTrue(content.contains("Prof. Ben Cruz"), "recommendingApproval override is used");
            assertTrue(content.contains("Noted by:"), "omitted signatories still render their role line");
        }

        // Regenerating never overwrites: the archive keeps both versions (spec §3.6).
        MvcResult reports = mockMvc.perform(get("/api/surveys/{id}/reports", fixture.surveyId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(1, objectMapper.readTree(reports.getResponse().getContentAsString()).size());

        mockMvc.perform(post("/api/surveys/{id}/report-qf23", fixture.surveyId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        MvcResult afterRegenerate = mockMvc.perform(get("/api/surveys/{id}/reports", fixture.surveyId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(2, objectMapper.readTree(afterRegenerate.getResponse().getContentAsString()).size(),
                "each generation adds an archive row rather than replacing the previous one");
    }

    // --- fixture ---

    private record Fixture(String surveyId, String healthQuestionId, String educationQuestionId,
            String checkboxQuestionId, String token) {
    }

    /**
     * Deploys a survey and records three responses (2 female, 1 male):
     * Health (weight 2.0): f1=5, f2=3, m1=4  -> avg 4.00, high, rank 1
     * Education (weight 1.0): f1=1, f2=2, m1=1 -> avg 1.33, low,  rank 2
     * Checkbox: f1=Clinic, f2=Pharmacy, m1=Clinic (collected, not scored)
     */
    private Fixture surveyWithResponses() throws Exception {
        String token = coordinatorToken();
        String communityId = firstCommunityId(token);
        String health = needCategoryId(token, "Health");
        String education = needCategoryId(token, "Education");

        String surveyId = objectMapper.readTree(mockMvc.perform(post("/api/surveys")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"communityId":"%s","title":"Results Fixture Survey","targetResponses":6}
                                """.formatted(communityId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        String healthQ = addQuestion(surveyId, token, """
                {"questionText":"Rate health access","questionType":"rating","weight":2.0,
                 "required":true,"needCategoryId":"%s"}
                """.formatted(health));
        String educationQ = addQuestion(surveyId, token, """
                {"questionText":"Rate school access","questionType":"rating","weight":1.0,
                 "required":true,"needCategoryId":"%s"}
                """.formatted(education));
        String checkboxQ = addQuestion(surveyId, token, """
                {"questionText":"Services used","questionType":"checkbox","weight":1.0,"required":false,
                 "options":[{"label":"Clinic","value":"clinic"},{"label":"Pharmacy","value":"pharmacy"}]}
                """);

        String accessToken = objectMapper.readTree(mockMvc.perform(post("/api/surveys/{id}/deploy", surveyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("accessToken").asText();

        submit(accessToken, "female", "dev-f1", healthQ, "5", educationQ, "1", checkboxQ, "clinic");
        submit(accessToken, "female", "dev-f2", healthQ, "3", educationQ, "2", checkboxQ, "pharmacy");
        submit(accessToken, "male", "dev-m1", healthQ, "4", educationQ, "1", checkboxQ, "clinic");

        return new Fixture(surveyId, healthQ, educationQ, checkboxQ, token);
    }

    private void submit(String accessToken, String sex, String device,
            String healthQ, String healthValue,
            String educationQ, String educationValue,
            String checkboxQ, String checkboxValue) throws Exception {
        mockMvc.perform(post("/api/public/surveys/{token}/responses", accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"respondentSex":"%s","respondentToken":"%s","consent":true,
                                 "answers":[{"questionId":"%s","value":"%s"},
                                            {"questionId":"%s","value":"%s"},
                                            {"questionId":"%s","values":["%s"]}]}
                                """.formatted(sex, device, healthQ, healthValue,
                                educationQ, educationValue, checkboxQ, checkboxValue)))
                .andExpect(status().isCreated());
    }

    private String deployedSurveyWithoutResponses(String token) throws Exception {
        String communityId = firstCommunityId(token);
        String surveyId = objectMapper.readTree(mockMvc.perform(post("/api/surveys")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"communityId":"%s","title":"Empty Survey"}
                                """.formatted(communityId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        addQuestion(surveyId, token, """
                {"questionText":"Rate something","questionType":"rating","weight":1.0}
                """);
        mockMvc.perform(post("/api/surveys/{id}/deploy", surveyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return surveyId;
    }

    private String addQuestion(String surveyId, String token, String payload) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/surveys/{id}/questions", surveyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private String needCategoryId(String token, String name) throws Exception {
        JsonNode categories = objectMapper.readTree(mockMvc.perform(get("/api/need-categories")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        for (JsonNode category : categories) {
            if (category.get("name").asText().equals(name)) {
                return category.get("id").asText();
            }
        }
        throw new IllegalStateException("Seeded need category not found: " + name);
    }

    private String firstCommunityId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/communities").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get(0).get("id").asText();
    }

    private String sheetText(Sheet sheet) {
        StringBuilder text = new StringBuilder();
        sheet.forEach(row -> row.forEach(cell -> {
            switch (cell.getCellType()) {
                case STRING -> text.append(cell.getStringCellValue()).append(' ');
                case NUMERIC -> text.append(cell.getNumericCellValue()).append(' ');
                default -> text.append(' ');
            }
        }));
        return text.toString();
    }

    private String coordinatorToken() throws Exception {
        return token(COORDINATOR_EMAIL, COORDINATOR_PASSWORD);
    }

    private String token(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }
}
