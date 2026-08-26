package com.cems.api;

import com.cems.api.service.EmailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module 5b coverage: the delivery phase of a program — activities, attendance and evaluations.
 *
 * <p>The phase gate is {@link #approvedProgramRunsThroughToCompleted()}: an approved program grows
 * activities, collects attendance (manually and by CSV), gets a post-evaluation, and completes
 * itself, with the GAD split correct at every step.
 *
 * <p>The other tests pin the rules that are easy to regress: attendance is blocked on cancelled
 * activities (409), delivery is blocked before approval (409) and for non-owners (403), CSV imports
 * are partial with a row-level report, and student volunteers see counts without names.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProgramActivityControllerIntegrationTest {

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

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmailService emailService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- the phase gate ---

    @Test
    @DisplayName("an approved program runs through activities and evaluation to completed, with correct F/M")
    void approvedProgramRunsThroughToCompleted() throws Exception {
        String faculty = token(FACULTY_EMAIL, FACULTY_PASSWORD);
        String programId = approvedProgram();

        assertEquals("approved", programStatus(programId, faculty));

        String activityId = createActivity(programId, faculty, "Session 1", "2026-10-05");

        // Attendance: two women, one man, entered by hand.
        addAttendance(activityId, faculty, "Maria Santos", "female", 34);
        addAttendance(activityId, faculty, "Ana Cruz", "female", 41);
        addAttendance(activityId, faculty, "Jose Rizal", "male", 29);

        JsonNode totals = json(mockMvc.perform(get("/api/activities/{id}/attendance/totals", activityId)
                        .header("Authorization", "Bearer " + faculty))
                .andExpect(status().isOk()));
        assertEquals(3, totals.get("total").asInt());
        assertEquals(2, totals.get("female").asInt());
        assertEquals(1, totals.get("male").asInt());

        // Marking the first activity done starts the program.
        markActivity(activityId, faculty, "done").andExpect(status().isOk());
        assertEquals("ongoing", programStatus(programId, faculty));

        // All activities settled, but no post-evaluation yet — so not complete.
        assertEquals("ongoing", programStatus(programId, faculty));

        // The post-evaluation is the last precondition; encoding it completes the program.
        mockMvc.perform(post("/api/activities/{id}/evaluations", activityId)
                        .header("Authorization", "Bearer " + faculty)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evalType":"post","respondentCount":3,"femaleCount":2,"maleCount":1,
                                 "avgRating":4.50,"notes":"Well received."}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.evalType").value("post"))
                .andExpect(jsonPath("$.unspecifiedCount").value(0));

        assertEquals("completed", programStatus(programId, faculty));

        // The program-level rollup agrees with the single activity.
        JsonNode programTotals = json(mockMvc.perform(
                        get("/api/programs/{id}/attendance-totals", programId)
                                .header("Authorization", "Bearer " + faculty))
                .andExpect(status().isOk()));
        assertEquals(3, programTotals.get("total").asInt());
        assertEquals(2, programTotals.get("female").asInt());
    }

    @Test
    @DisplayName("a program with an unfinished activity does not complete, even with a post-evaluation")
    void completionRequiresEveryActivitySettled() throws Exception {
        String faculty = token(FACULTY_EMAIL, FACULTY_PASSWORD);
        String programId = approvedProgram();

        String first = createActivity(programId, faculty, "Session 1", "2026-10-05");
        String second = createActivity(programId, faculty, "Session 2", "2026-10-12");

        markActivity(first, faculty, "done").andExpect(status().isOk());
        assertEquals("ongoing", programStatus(programId, faculty));

        encodePostEvaluation(first, faculty);
        assertEquals("ongoing", programStatus(programId, faculty), "session 2 is still scheduled");

        markActivity(second, faculty, "done").andExpect(status().isOk());
        assertEquals("completed", programStatus(programId, faculty));
    }

    @Test
    @DisplayName("cancelling the last outstanding activity also settles the program")
    void cancelledActivityCountsAsSettledForCompletion() throws Exception {
        String faculty = token(FACULTY_EMAIL, FACULTY_PASSWORD);
        String programId = approvedProgram();

        String first = createActivity(programId, faculty, "Session 1", "2026-10-05");
        String second = createActivity(programId, faculty, "Session 2", "2026-10-12");

        markActivity(first, faculty, "done").andExpect(status().isOk());
        encodePostEvaluation(first, faculty);
        markActivity(second, faculty, "cancelled").andExpect(status().isOk());

        assertEquals("completed", programStatus(programId, faculty));
    }

    // --- attendance rules ---

    @Test
    @DisplayName("attendance cannot be recorded against a cancelled activity")
    void attendanceIsBlockedOnCancelledActivities() throws Exception {
        String faculty = token(FACULTY_EMAIL, FACULTY_PASSWORD);
        String activityId = createActivity(approvedProgram(), faculty, "Cancelled session", "2026-10-05");
        markActivity(activityId, faculty, "cancelled").andExpect(status().isOk());

        mockMvc.perform(post("/api/activities/{id}/attendance", activityId)
                        .header("Authorization", "Bearer " + faculty)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attendeeName\":\"Maria Santos\",\"sex\":\"female\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("sex is required on every attendance row")
    void attendanceRequiresSex() throws Exception {
        String faculty = token(FACULTY_EMAIL, FACULTY_PASSWORD);
        String activityId = createActivity(approvedProgram(), faculty, "Session", "2026-10-05");

        mockMvc.perform(post("/api/activities/{id}/attendance", activityId)
                        .header("Authorization", "Bearer " + faculty)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attendeeName\":\"Maria Santos\"}"))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(post("/api/activities/{id}/attendance", activityId)
                        .header("Authorization", "Bearer " + faculty)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attendeeName\":\"Maria Santos\",\"sex\":\"unknown\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // --- CSV import ---

    @Test
    @DisplayName("CSV import saves valid rows and reports invalid ones by line number")
    void csvImportIsPartialWithRowLevelErrors() throws Exception {
        String faculty = token(FACULTY_EMAIL, FACULTY_PASSWORD);
        String activityId = createActivity(approvedProgram(), faculty, "Session", "2026-10-05");

        String csv = """
                name,sex,age,sector
                Maria Santos,female,34,
                Jose Rizal,male,29,
                Bad Row,alien,30,
                Ana Cruz,F,41,
                ,female,22,
                Old Person,male,999,
                """;

        JsonNode result = json(mockMvc.perform(multipart("/api/activities/{id}/attendance/import", activityId)
                        .file(new MockMultipartFile("file", "attendance.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + faculty))
                .andExpect(status().isOk()));

        // Maria, Jose and Ana land; the alien sex, the nameless row and the impossible age do not.
        assertEquals(3, result.get("imported").asInt());
        assertEquals(3, result.get("skipped").asInt());

        JsonNode errors = result.get("errors");
        assertEquals(3, errors.size());
        // Line numbers count the header, so they match what the user sees in a spreadsheet.
        assertEquals(4, errors.get(0).get("line").asInt());
        assertTrue(errors.get(0).get("message").asText().contains("alien"));
        assertEquals(6, errors.get(1).get("line").asInt());
        assertTrue(errors.get(1).get("message").asText().contains("Name is required"));
        assertEquals(7, errors.get(2).get("line").asInt());

        // "F" was accepted as female, so the running split is 2F / 1M.
        assertEquals(3, result.get("totals").get("total").asInt());
        assertEquals(2, result.get("totals").get("female").asInt());
        assertEquals(1, result.get("totals").get("male").asInt());
    }

    @Test
    @DisplayName("a CSV with no header and quoted commas still imports")
    void csvImportHandlesHeaderlessAndQuotedRows() throws Exception {
        String faculty = token(FACULTY_EMAIL, FACULTY_PASSWORD);
        String activityId = createActivity(approvedProgram(), faculty, "Session", "2026-10-05");

        String csv = "\"Santos, Maria\",female,34,\nJose Rizal,male,29,\n";
        JsonNode result = json(mockMvc.perform(multipart("/api/activities/{id}/attendance/import", activityId)
                        .file(new MockMultipartFile("file", "attendance.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + faculty))
                .andExpect(status().isOk()));

        assertEquals(2, result.get("imported").asInt());
        assertEquals(0, result.get("skipped").asInt());

        JsonNode rows = json(mockMvc.perform(get("/api/activities/{id}/attendance", activityId)
                        .header("Authorization", "Bearer " + faculty))
                .andExpect(status().isOk()));
        boolean foundQuotedName = false;
        for (JsonNode row : rows) {
            if ("Santos, Maria".equals(row.get("attendeeName").asText())) {
                foundQuotedName = true;
            }
        }
        assertTrue(foundQuotedName, "a quoted cell containing a comma must survive the split");
    }

    // --- evaluations ---

    @Test
    @DisplayName("an evaluation whose F/M split exceeds its respondent count is rejected")
    void evaluationSplitCannotExceedRespondents() throws Exception {
        String faculty = token(FACULTY_EMAIL, FACULTY_PASSWORD);
        String activityId = createActivity(approvedProgram(), faculty, "Session", "2026-10-05");

        mockMvc.perform(post("/api/activities/{id}/evaluations", activityId)
                        .header("Authorization", "Bearer " + faculty)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evalType\":\"post\",\"respondentCount\":10,\"femaleCount\":8,\"maleCount\":5}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.femaleCount").exists())
                .andExpect(jsonPath("$.error.fields.maleCount").exists());
    }

    @Test
    @DisplayName("respondents who did not state a sex are reported, not bucketed")
    void evaluationAllowsAnUnspecifiedRemainder() throws Exception {
        String faculty = token(FACULTY_EMAIL, FACULTY_PASSWORD);
        String activityId = createActivity(approvedProgram(), faculty, "Session", "2026-10-05");

        mockMvc.perform(post("/api/activities/{id}/evaluations", activityId)
                        .header("Authorization", "Bearer " + faculty)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evalType\":\"pre\",\"respondentCount\":10,\"femaleCount\":4,\"maleCount\":3}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.unspecifiedCount").value(3));
    }

    // --- permissions ---

    @Test
    @DisplayName("student volunteers see attendance counts but not beneficiary names")
    void studentVolunteersSeeMaskedAttendance() throws Exception {
        String coordinator = token(COORDINATOR_EMAIL, COORDINATOR_PASSWORD);
        String programId = approvedProgramOwnedBy(coordinator);
        String activityId = createActivity(programId, coordinator, "Session", "2026-10-05");
        addAttendance(activityId, coordinator, "Maria Santos", "female", 34);

        // A student volunteer only sees a program they are assigned to — that assignment is what
        // spec §2.2's "the programs they help run" means.
        assignMember(programId, coordinator, userId(STUDENT_EMAIL, STUDENT_PASSWORD));
        String student = token(STUDENT_EMAIL, STUDENT_PASSWORD);

        // Writing is refused outright.
        mockMvc.perform(post("/api/activities/{id}/attendance", activityId)
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attendeeName\":\"Someone\",\"sex\":\"male\"}"))
                .andExpect(status().isForbidden());

        // Totals are visible; the roster is masked.
        JsonNode totals = json(mockMvc.perform(get("/api/activities/{id}/attendance/totals", activityId)
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk()));
        assertEquals(1, totals.get("total").asInt());
        assertEquals(1, totals.get("female").asInt());

        JsonNode rows = json(mockMvc.perform(get("/api/activities/{id}/attendance", activityId)
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isOk()));
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).get("attendeeName").isNull(),
                "beneficiary names must be masked for student volunteers (plan §4 open question #1)");
        assertEquals("female", rows.get(0).get("sex").asText(), "the GAD figure is still readable");
    }

    @Test
    @DisplayName("activities cannot be added before the program is approved")
    void deliveryIsBlockedBeforeApproval() throws Exception {
        String faculty = token(FACULTY_EMAIL, FACULTY_PASSWORD);
        String draftId = completeProposal(faculty);

        mockMvc.perform(post("/api/programs/{id}/activities", draftId)
                        .header("Authorization", "Bearer " + faculty)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Too early\",\"activityDate\":\"2026-10-05\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("a faculty member cannot add activities to someone else's program")
    void deliveryIsBlockedForNonOwners() throws Exception {
        String coordinator = token(COORDINATOR_EMAIL, COORDINATOR_PASSWORD);
        String programId = approvedProgramOwnedBy(coordinator);

        // The seeded faculty account neither created nor leads this one.
        mockMvc.perform(post("/api/programs/{id}/activities", programId)
                        .header("Authorization", "Bearer " + token(FACULTY_EMAIL, FACULTY_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Not mine\",\"activityDate\":\"2026-10-05\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an end time before the start time is rejected")
    void activityTimesMustBeOrdered() throws Exception {
        String faculty = token(FACULTY_EMAIL, FACULTY_PASSWORD);
        String programId = approvedProgram();

        mockMvc.perform(post("/api/programs/{id}/activities", programId)
                        .header("Authorization", "Bearer " + faculty)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Backwards","activityDate":"2026-10-05",
                                 "startTime":"15:00:00","endTime":"09:00:00"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("a soft-deleted activity stops counting toward totals and completion")
    void deletingAnActivityRemovesItFromRollups() throws Exception {
        String faculty = token(FACULTY_EMAIL, FACULTY_PASSWORD);
        String programId = approvedProgram();

        String kept = createActivity(programId, faculty, "Kept", "2026-10-05");
        String removed = createActivity(programId, faculty, "Removed", "2026-10-12");
        addAttendance(kept, faculty, "Maria Santos", "female", 34);
        addAttendance(removed, faculty, "Jose Rizal", "male", 29);

        JsonNode before = json(mockMvc.perform(get("/api/programs/{id}/attendance-totals", programId)
                        .header("Authorization", "Bearer " + faculty))
                .andExpect(status().isOk()));
        assertEquals(2, before.get("total").asInt());

        mockMvc.perform(delete("/api/activities/{id}", removed)
                        .header("Authorization", "Bearer " + faculty))
                .andExpect(status().isOk());

        JsonNode after = json(mockMvc.perform(get("/api/programs/{id}/attendance-totals", programId)
                        .header("Authorization", "Bearer " + faculty))
                .andExpect(status().isOk()));
        assertEquals(1, after.get("total").asInt(), "the deleted activity's attendee must drop out");
        assertEquals(1, after.get("female").asInt());

        JsonNode activities = json(mockMvc.perform(get("/api/programs/{id}/activities", programId)
                        .header("Authorization", "Bearer " + faculty))
                .andExpect(status().isOk()));
        assertEquals(1, activities.size());
        assertFalse(activities.get(0).get("id").asText().equals(removed));
    }

    @Test
    @DisplayName("the activity response tells the client whether attendance may be recorded")
    void cancelledActivityReportsThatAttendanceIsClosed() throws Exception {
        String faculty = token(FACULTY_EMAIL, FACULTY_PASSWORD);
        String activityId = createActivity(approvedProgram(), faculty, "Session", "2026-10-05");

        JsonNode open = json(mockMvc.perform(get("/api/activities/{id}", activityId)
                        .header("Authorization", "Bearer " + faculty))
                .andExpect(status().isOk()));
        assertTrue(open.get("canRecordAttendance").asBoolean());

        markActivity(activityId, faculty, "cancelled").andExpect(status().isOk());

        JsonNode cancelled = json(mockMvc.perform(get("/api/activities/{id}", activityId)
                        .header("Authorization", "Bearer " + faculty))
                .andExpect(status().isOk()));
        assertFalse(cancelled.get("canRecordAttendance").asBoolean());
    }

    @Test
    @DisplayName("evaluation counts are readable but a masked reader still gets no names")
    void evaluationSummariesCarryTheirOwnSplit() throws Exception {
        String coordinator = token(COORDINATOR_EMAIL, COORDINATOR_PASSWORD);
        String programId = approvedProgramOwnedBy(coordinator);
        String activityId = createActivity(programId, coordinator, "Session", "2026-10-05");
        encodePostEvaluation(activityId, coordinator);
        assignMember(programId, coordinator, userId(STUDENT_EMAIL, STUDENT_PASSWORD));

        JsonNode evaluations = json(mockMvc.perform(get("/api/activities/{id}/evaluations", activityId)
                        .header("Authorization", "Bearer " + token(STUDENT_EMAIL, STUDENT_PASSWORD)))
                .andExpect(status().isOk()));
        assertEquals(1, evaluations.size());
        assertEquals(2, evaluations.get(0).get("femaleCount").asInt());
        assertNull(evaluations.get(0).get("filePath"), "storage paths are never exposed");
    }

    // --- helpers ---

    /** An approved program led by the seeded faculty account. */
    private String approvedProgram() throws Exception {
        return approvedProgramOwnedBy(token(FACULTY_EMAIL, FACULTY_PASSWORD));
    }

    /** Drives a fresh proposal through the whole four-stage chain so delivery can begin. */
    private String approvedProgramOwnedBy(String authorToken) throws Exception {
        String programId = completeProposal(authorToken);

        mockMvc.perform(post("/api/programs/{id}/submit", programId)
                        .header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk());
        stageAction(programId, "review", "{\"action\":\"note\"}", token(COORDINATOR_EMAIL, COORDINATOR_PASSWORD))
                .andExpect(status().isOk());
        stageAction(programId, "recommend", "{\"action\":\"recommend\"}",
                token(CAMPUS_COORD_EMAIL, CAMPUS_COORD_PASSWORD))
                .andExpect(status().isOk());
        stageAction(programId, "approve", "{\"action\":\"approve\"}",
                token(CAMPUS_ADMIN_EMAIL, CAMPUS_ADMIN_PASSWORD))
                .andExpect(status().isOk());
        return programId;
    }

    private String completeProposal(String authorToken) throws Exception {
        String body = """
                {"title":"Delivery test %s","communityId":"%s","programTypeId":"%s",
                 "objectives":"Deliver the programme.","targetBeneficiaries":40,
                 "proposedDate":"2026-10-01","endDate":"2026-10-31",
                 "venue":"Barangay Hall","budgetRequested":25000}
                """.formatted(UUID.randomUUID(), communityId(authorToken), programTypeId(authorToken));
        return json(mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + authorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated()))
                .get("id").asText();
    }

    private String createActivity(String programId, String token, String title, String date) throws Exception {
        return json(mockMvc.perform(post("/api/programs/{id}/activities", programId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"%s\",\"activityDate\":\"%s\"}".formatted(title, date)))
                .andExpect(status().isCreated()))
                .get("id").asText();
    }

    private ResultActions markActivity(String activityId, String token, String status) throws Exception {
        return mockMvc.perform(patch("/api/activities/{id}", activityId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Session\",\"activityDate\":\"2026-10-05\",\"status\":\"%s\"}"
                        .formatted(status)));
    }

    private void addAttendance(String activityId, String token, String name, String sex, int age)
            throws Exception {
        mockMvc.perform(post("/api/activities/{id}/attendance", activityId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attendeeName\":\"%s\",\"sex\":\"%s\",\"age\":%d}"
                                .formatted(name, sex, age)))
                .andExpect(status().isCreated());
    }

    private void encodePostEvaluation(String activityId, String token) throws Exception {
        mockMvc.perform(post("/api/activities/{id}/evaluations", activityId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evalType\":\"post\",\"respondentCount\":3,\"femaleCount\":2,\"maleCount\":1}"))
                .andExpect(status().isCreated());
    }

    private void assignMember(String programId, String assignerToken, String userId) throws Exception {
        mockMvc.perform(post("/api/programs/{id}/members", programId)
                        .header("Authorization", "Bearer " + assignerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\",\"roleInProgram\":\"volunteer\"}".formatted(userId)))
                .andExpect(status().isCreated());
    }

    /** Resolves a seeded account's id through its own /users/me, which every role may read. */
    private String userId(String email, String password) throws Exception {
        return json(mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + token(email, password)))
                .andExpect(status().isOk()))
                .get("id").asText();
    }

    private ResultActions stageAction(String programId, String endpoint, String body, String token)
            throws Exception {
        return mockMvc.perform(post("/api/programs/{id}/" + endpoint, programId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String programStatus(String programId, String token) throws Exception {
        return json(mockMvc.perform(get("/api/programs/{id}", programId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()))
                .get("status").asText();
    }

    private String communityId(String token) throws Exception {
        return json(mockMvc.perform(get("/api/communities")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()))
                .get("data").get(0).get("id").asText();
    }

    private String programTypeId(String token) throws Exception {
        return json(mockMvc.perform(get("/api/program-types")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()))
                .get(0).get("id").asText();
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
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
