package com.cems.api;

import com.cems.api.service.EmailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module 6 coverage: the M&amp;E dashboard, its period selector, its drill-downs and its exports.
 *
 * <p>The test database is shared across the suite, so figures are asserted as <em>deltas</em> around
 * a fixture rather than as absolute totals — except where the point of the assertion is that two
 * endpoints agree, which holds whatever else is in the database.
 *
 * <p>The fixture deliberately lands in <b>S2 AY 2026-2027</b> (Jan–May 2027) while every other test
 * class proposes programs on 2026-10-01 (S1 AY 2026-2027). That separation is what lets
 * {@link #theSelectedPeriodActuallyFiltersTheFigures()} prove the period clause does something.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MonitoringDashboardControllerIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@cems.com";
    private static final String ADMIN_PASSWORD = "Admin123!";
    private static final String CAMPUS_ADMIN_EMAIL = "campus.admin@cems.com";
    private static final String CAMPUS_ADMIN_PASSWORD = "CampusAdmin123!";
    private static final String CAMPUS_COORD_EMAIL = "campus.coordinator@cems.com";
    private static final String CAMPUS_COORD_PASSWORD = "CampusCoord123!";
    private static final String COORDINATOR_EMAIL = "coordinator@cems.com";
    private static final String COORDINATOR_PASSWORD = "Coordinator123!";
    private static final String FACULTY_EMAIL = "faculty@cems.com";
    private static final String FACULTY_PASSWORD = "Faculty123!";
    private static final String STUDENT_EMAIL = "student@cems.com";
    private static final String STUDENT_PASSWORD = "Student123!";

    /** S2 AY 2026-2027 — seeded by V12, and the period this class's fixtures fall in. */
    private static final String FIXTURE_PERIOD = "50000000-0000-0000-0000-000000000005";
    private static final String FIXTURE_DATE = "2027-02-10";
    /** S1 AY 2025-2026 — a period no fixture in this suite touches. */
    private static final String EMPTY_PERIOD = "50000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmailService emailService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- the period lookup ---

    @Test
    void periodsAreListedInCalendarOrderWithExactlyOneCurrent() throws Exception {
        JsonNode periods = json(mockMvc.perform(get("/api/academic-periods")
                        .header("Authorization", "Bearer " + token(COORDINATOR_EMAIL, COORDINATOR_PASSWORD)))
                .andExpect(status().isOk()));

        assertTrue(periods.size() >= 6, "the migration seeds six terms");

        String previousStart = "";
        int currentCount = 0;
        for (JsonNode period : periods) {
            String startsOn = period.get("startsOn").asText();
            assertTrue(startsOn.compareTo(previousStart) >= 0, "periods must be in calendar order");
            previousStart = startsOn;
            assertTrue(period.get("endsOn").asText().compareTo(startsOn) >= 0);
            if (period.get("current").asBoolean()) {
                currentCount += 1;
            }
        }
        assertEquals(1, currentCount, "exactly one period may be marked current");
    }

    /**
     * Faculty cannot open the M&amp;E dashboard but must still be able to filter their own proposal
     * list by term, so the calendar itself is open to every authenticated role.
     */
    @Test
    void everyRoleCanReadTheCalendarEvenWithoutDashboardAccess() throws Exception {
        mockMvc.perform(get("/api/academic-periods")
                        .header("Authorization", "Bearer " + token(STUDENT_EMAIL, STUDENT_PASSWORD)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/academic-periods"))
                .andExpect(status().isUnauthorized());
    }

    // --- access ---

    @Test
    @DisplayName("the M&E dashboard is coordinator/admin only; faculty and volunteers get 403")
    void monitoringDashboardIsRestrictedToCoordinatorsAndAdministrators() throws Exception {
        mockMvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());

        for (String[] allowed : new String[][] {
                {ADMIN_EMAIL, ADMIN_PASSWORD},
                {CAMPUS_ADMIN_EMAIL, CAMPUS_ADMIN_PASSWORD},
                {CAMPUS_COORD_EMAIL, CAMPUS_COORD_PASSWORD},
                {COORDINATOR_EMAIL, COORDINATOR_PASSWORD}}) {
            mockMvc.perform(get("/api/dashboard")
                            .header("Authorization", "Bearer " + token(allowed[0], allowed[1])))
                    .andExpect(status().isOk());
        }

        for (String[] denied : new String[][] {
                {FACULTY_EMAIL, FACULTY_PASSWORD},
                {STUDENT_EMAIL, STUDENT_PASSWORD}}) {
            mockMvc.perform(get("/api/dashboard")
                            .header("Authorization", "Bearer " + token(denied[0], denied[1])))
                    .andExpect(status().isForbidden());
        }

        // The personal landing overview stays open to everyone — the two are separate payloads.
        mockMvc.perform(get("/api/dashboard/overview")
                        .header("Authorization", "Bearer " + token(FACULTY_EMAIL, FACULTY_PASSWORD)))
                .andExpect(status().isOk());
    }

    /**
     * A stale bookmark must not quietly widen to campus-wide figures while still showing a period
     * label — that would be a wrong number presented as a right one.
     */
    @Test
    void anUnknownPeriodIsNotFoundRatherThanSilentlyAllPeriods() throws Exception {
        mockMvc.perform(get("/api/dashboard")
                        .param("periodId", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + token(COORDINATOR_EMAIL, COORDINATOR_PASSWORD)))
                .andExpect(status().isNotFound());
    }

    @Test
    void omittingThePeriodReturnsEveryPeriodWithANullPeriodLabel() throws Exception {
        JsonNode dashboard = dashboard(token(COORDINATOR_EMAIL, COORDINATOR_PASSWORD), null);
        assertTrue(dashboard.get("period").isNull(),
                "an all-periods payload must not invent a period label");
        assertNotNull(dashboard.get("generatedAt"));
    }

    // --- the aggregates ---

    @Test
    @DisplayName("a delivered program moves every KPI it should, and none it should not")
    void deliveredProgramFeedsTheKpisAndTheCompletionTable() throws Exception {
        String token = token(COORDINATOR_EMAIL, COORDINATOR_PASSWORD);
        JsonNode before = dashboard(token, FIXTURE_PERIOD).get("kpis");

        String programId = deliveredProgram();

        JsonNode after = dashboard(token, FIXTURE_PERIOD);
        JsonNode kpis = after.get("kpis");
        assertEquals(1, delta(before, kpis, "programsTotal"));
        assertEquals(1, delta(before, kpis, "programsCompleted"));
        assertEquals(3, delta(before, kpis, "beneficiariesTotal"));
        assertEquals(2, delta(before, kpis, "beneficiariesFemale"));
        assertEquals(1, delta(before, kpis, "beneficiariesMale"));
        assertTrue(kpis.get("communitiesServed").asLong() >= 1);
        assertTrue(kpis.get("facultyInvolved").asLong() >= 1);

        // Attendance sex is NOT NULL and CHECK-constrained, so this identity must hold exactly.
        assertEquals(kpis.get("beneficiariesTotal").asLong(),
                kpis.get("beneficiariesFemale").asLong() + kpis.get("beneficiariesMale").asLong());

        // The method note travels with the number rather than being retyped in the UI.
        assertFalse(kpis.get("beneficiaryMethod").asText().isBlank());

        JsonNode row = completionRow(after, programId);
        assertNotNull(row, "the delivered program must appear in the completion table");
        assertEquals(40, row.get("targetBeneficiaries").asInt());
        assertEquals(3, row.get("actualTotal").asLong());
        assertEquals(2, row.get("actualFemale").asLong());
        assertEquals(1, row.get("actualMale").asLong());
        assertTrue(row.get("hasPostEvaluation").asBoolean());
        assertFalse(row.get("hasPreEvaluation").asBoolean(), "no pre-evaluation was encoded");
        assertEquals("completed", row.get("status").asText());
    }

    /** If the period clause did nothing, this fixture would show up in every period. */
    @Test
    void theSelectedPeriodActuallyFiltersTheFigures() throws Exception {
        String token = token(COORDINATOR_EMAIL, COORDINATOR_PASSWORD);
        JsonNode before = dashboard(token, EMPTY_PERIOD).get("kpis");

        String programId = deliveredProgram();

        JsonNode after = dashboard(token, EMPTY_PERIOD);
        assertEquals(0, delta(before, after.get("kpis"), "programsTotal"));
        assertEquals(0, delta(before, after.get("kpis"), "beneficiariesTotal"));
        assertNull(completionRow(after, programId),
                "a program proposed in another term must not appear in this one");
        assertEquals("S1 AY 2025-2026", after.get("period").get("label").asText());
    }

    /**
     * Spec Module 6 AC 6 — every count must be traceable to the list behind it. The dashboard, the
     * programs list and the list's own tab badges apply one period rule, so all three agree.
     */
    @Test
    void everyKpiCountEqualsTheListItDrillsIntoAndTheTabBadgesAboveIt() throws Exception {
        String token = token(COORDINATOR_EMAIL, COORDINATOR_PASSWORD);
        deliveredProgram();

        long fromDashboard = dashboard(token, FIXTURE_PERIOD).get("kpis").get("programsTotal").asLong();

        long fromList = json(mockMvc.perform(get("/api/programs")
                        .param("periodId", FIXTURE_PERIOD)
                        .param("perPage", "100")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()))
                .get("meta").get("total").asLong();

        long fromStats = json(mockMvc.perform(get("/api/programs/stats")
                        .param("periodId", FIXTURE_PERIOD)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()))
                .get("total").asLong();

        assertEquals(fromDashboard, fromList, "KPI and drill-down list must count the same programs");
        assertEquals(fromDashboard, fromStats, "tab badges must count the same programs as the rows");
        assertTrue(fromDashboard >= 1);
    }

    /**
     * Attendees recorded without a sector are real beneficiaries. They must reach the chart under an
     * explicit label rather than being dropped by a join and leaving the chart short of the KPI.
     */
    @Test
    void sectorChartAccountsForEveryBeneficiaryIncludingThoseWithoutASector() throws Exception {
        String token = token(COORDINATOR_EMAIL, COORDINATOR_PASSWORD);
        deliveredProgram();

        JsonNode dashboard = dashboard(token, FIXTURE_PERIOD);
        long charted = 0;
        boolean sawUnspecified = false;
        for (JsonNode sector : dashboard.get("beneficiariesBySector")) {
            assertEquals(sector.get("total").asLong(),
                    sector.get("female").asLong() + sector.get("male").asLong());
            charted += sector.get("total").asLong();
            if (sector.get("sectorId").isNull()) {
                sawUnspecified = true;
                assertEquals("Not specified", sector.get("sectorName").asText());
            }
        }
        assertEquals(dashboard.get("kpis").get("beneficiariesTotal").asLong(), charted,
                "the sector chart must account for every beneficiary in the KPI above it");
        assertTrue(sawUnspecified, "the fixture records attendance with no sector");
    }

    @Test
    void programsByTypeIsOrderedLargestFirst() throws Exception {
        deliveredProgram();
        JsonNode types = dashboard(token(COORDINATOR_EMAIL, COORDINATOR_PASSWORD), FIXTURE_PERIOD)
                .get("programsByType");
        assertTrue(types.size() >= 1);

        long previous = Long.MAX_VALUE;
        for (JsonNode type : types) {
            long programs = type.get("programs").asLong();
            assertTrue(programs <= previous, "bars must be ordered largest first");
            previous = programs;
            assertFalse(type.get("programTypeName").asText().isBlank());
        }
    }

    // --- exports ---

    @Test
    @DisplayName("the XLSX export carries the same totals as the payload it was rendered from")
    void xlsxExportMatchesTheDashboardTotals() throws Exception {
        String token = token(COORDINATOR_EMAIL, COORDINATOR_PASSWORD);
        deliveredProgram();
        JsonNode kpis = dashboard(token, FIXTURE_PERIOD).get("kpis");

        byte[] content = mockMvc.perform(get("/api/dashboard/export")
                        .param("periodId", FIXTURE_PERIOD)
                        .param("format", "xlsx")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header -> assertTrue(header.getResponse()
                        .getHeader("Content-Disposition").contains("attachment;")))
                .andReturn().getResponse().getContentAsByteArray();

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            assertNotNull(workbook.getSheet("KPIs"));
            assertNotNull(workbook.getSheet("Programs by Type"));
            assertNotNull(workbook.getSheet("Beneficiaries by Sector"));
            assertNotNull(workbook.getSheet("Beneficiaries by Sex"));
            assertNotNull(workbook.getSheet("Program Completion"));

            assertEquals(kpis.get("beneficiariesTotal").asLong(),
                    (long) numericCell(workbook.getSheet("KPIs"), "Beneficiaries reached (total)"));
            assertEquals(kpis.get("beneficiariesFemale").asLong(),
                    (long) numericCell(workbook.getSheet("KPIs"), "Beneficiaries reached (female)"));
            assertEquals(kpis.get("programsCompleted").asLong(),
                    (long) numericCell(workbook.getSheet("KPIs"), "Programs completed"));

            // The GAD sheet must reconcile against itself, since this is the sheet GAD reporting uses.
            Sheet bySex = workbook.getSheet("Beneficiaries by Sex");
            assertEquals(bySex.getRow(1).getCell(1).getNumericCellValue()
                            + bySex.getRow(2).getCell(1).getNumericCellValue(),
                    bySex.getRow(3).getCell(1).getNumericCellValue());
        }
    }

    @Test
    void pdfExportIsAValidDocumentAndUnknownFormatsAreRejected() throws Exception {
        String token = token(COORDINATOR_EMAIL, COORDINATOR_PASSWORD);

        byte[] pdf = mockMvc.perform(get("/api/dashboard/export")
                        .param("periodId", FIXTURE_PERIOD)
                        .param("format", "pdf")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertTrue(new String(pdf, 0, 8, StandardCharsets.ISO_8859_1).startsWith("%PDF-1.4"));
        assertTrue(pdf.length > 1000, "a rendered snapshot is not a stub");

        // 422, matching the survey export's identical rejection — the project maps a bad argument
        // value to Unprocessable Entity throughout rather than 400.
        mockMvc.perform(get("/api/dashboard/export")
                        .param("format", "docx")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void exportsAreClosedToRolesThatCannotOpenTheDashboard() throws Exception {
        mockMvc.perform(get("/api/dashboard/export")
                        .header("Authorization", "Bearer " + token(FACULTY_EMAIL, FACULTY_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    // --- fixtures ---

    /**
     * A program proposed inside {@link #FIXTURE_PERIOD}, driven through approval and delivery:
     * one activity, three attendees (2F / 1M, no sector), marked done, post-evaluation encoded —
     * which leaves it {@code completed}.
     */
    private String deliveredProgram() throws Exception {
        String faculty = token(FACULTY_EMAIL, FACULTY_PASSWORD);
        String programId = json(mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + faculty)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"M&E fixture %s","communityId":"%s","programTypeId":"%s",
                                 "objectives":"Deliver the programme.","targetBeneficiaries":40,
                                 "proposedDate":"%s","venue":"Barangay Hall","budgetRequested":25000}
                                """.formatted(UUID.randomUUID(), communityId(faculty),
                                programTypeId(faculty), FIXTURE_DATE)))
                .andExpect(status().isCreated()))
                .get("id").asText();

        mockMvc.perform(post("/api/programs/{id}/submit", programId)
                        .header("Authorization", "Bearer " + faculty))
                .andExpect(status().isOk());
        stage(programId, "review", "{\"action\":\"note\"}", COORDINATOR_EMAIL, COORDINATOR_PASSWORD);
        stage(programId, "recommend", "{\"action\":\"recommend\"}", CAMPUS_COORD_EMAIL, CAMPUS_COORD_PASSWORD);
        stage(programId, "approve", "{\"action\":\"approve\"}", CAMPUS_ADMIN_EMAIL, CAMPUS_ADMIN_PASSWORD);

        String activityId = json(mockMvc.perform(post("/api/programs/{id}/activities", programId)
                        .header("Authorization", "Bearer " + faculty)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Session 1\",\"activityDate\":\"%s\"}".formatted(FIXTURE_DATE)))
                .andExpect(status().isCreated()))
                .get("id").asText();

        attend(activityId, faculty, "Fixture Female A", "female");
        attend(activityId, faculty, "Fixture Female B", "female");
        attend(activityId, faculty, "Fixture Male A", "male");

        mockMvc.perform(patch("/api/activities/{id}", activityId)
                        .header("Authorization", "Bearer " + faculty)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Session 1\",\"activityDate\":\"%s\",\"status\":\"done\"}"
                                .formatted(FIXTURE_DATE)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/activities/{id}/evaluations", activityId)
                        .header("Authorization", "Bearer " + faculty)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evalType\":\"post\",\"respondentCount\":3,\"femaleCount\":2,\"maleCount\":1}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/programs/{id}", programId)
                        .header("Authorization", "Bearer " + faculty))
                .andExpect(jsonPath("$.status").value("completed"));
        return programId;
    }

    private void stage(String programId, String endpoint, String body, String email, String password)
            throws Exception {
        mockMvc.perform(post("/api/programs/{id}/" + endpoint, programId)
                        .header("Authorization", "Bearer " + token(email, password))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private void attend(String activityId, String token, String name, String sex) throws Exception {
        mockMvc.perform(post("/api/activities/{id}/attendance", activityId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attendeeName\":\"%s\",\"sex\":\"%s\"}".formatted(name, sex)))
                .andExpect(status().isCreated());
    }

    // --- helpers ---

    private JsonNode dashboard(String token, String periodId) throws Exception {
        var request = get("/api/dashboard").header("Authorization", "Bearer " + token);
        if (periodId != null) {
            request = request.param("periodId", periodId);
        }
        return json(mockMvc.perform(request).andExpect(status().isOk()));
    }

    private JsonNode completionRow(JsonNode dashboard, String programId) {
        for (JsonNode row : dashboard.get("completion")) {
            if (row.get("programId").asText().equals(programId)) {
                return row;
            }
        }
        return null;
    }

    /** Reads the value beside a label in a two-column sheet. */
    private double numericCell(Sheet sheet, String label) {
        for (Row row : sheet) {
            if (row.getCell(0) != null && label.equals(row.getCell(0).getStringCellValue())) {
                return row.getCell(1).getNumericCellValue();
            }
        }
        throw new IllegalStateException("Label not found in sheet: " + label);
    }

    private static long delta(JsonNode before, JsonNode after, String field) {
        return after.get(field).asLong() - before.get(field).asLong();
    }

    private String communityId(String token) throws Exception {
        return json(mockMvc.perform(get("/api/communities").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()))
                .get("data").get(0).get("id").asText();
    }

    private String programTypeId(String token) throws Exception {
        return json(mockMvc.perform(get("/api/program-types").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()))
                .get(0).get("id").asText();
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private String token(String email, String password) throws Exception {
        return json(mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk()))
                .get("accessToken").asText();
    }
}
