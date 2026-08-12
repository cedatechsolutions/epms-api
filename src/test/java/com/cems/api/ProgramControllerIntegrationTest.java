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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module 5a coverage: the four-stage approval chain end to end, the two failure modes that look
 * alike (wrong stage → 409, wrong role → 403), draft-vs-submit validation, faculty visibility, and
 * the two Phase-1 stubs this module closes (community delete-block and history).
 *
 * <p>The transition table itself is exhaustively covered by {@code ProgramStateMachineTest}; this
 * suite asserts it end-to-end through the API with real seeded role accounts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProgramControllerIntegrationTest {

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

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmailService emailService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- the happy path (the phase gate) ---

    @Test
    @DisplayName("a proposal travels draft -> approved through four different role accounts")
    void fullApprovalChain() throws Exception {
        String facultyToken = token(FACULTY_EMAIL, FACULTY_PASSWORD);
        String programId = completeProposal(facultyToken);

        assertEquals("draft", statusOf(programId, facultyToken));

        mockMvc.perform(post("/api/programs/{id}/submit", programId)
                        .header("Authorization", "Bearer " + facultyToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("submitted"));

        stageAction(programId, "review", "{\"action\":\"note\",\"comment\":\"Endorsed.\"}",
                token(COORDINATOR_EMAIL, COORDINATOR_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("coordinator_review"));

        stageAction(programId, "recommend", "{\"action\":\"recommend\"}",
                token(CAMPUS_COORD_EMAIL, CAMPUS_COORD_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("recommending_approval"));

        stageAction(programId, "approve", "{\"action\":\"approve\",\"budgetApproved\":45000}",
                token(CAMPUS_ADMIN_EMAIL, CAMPUS_ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.budgetApproved").value(45000));

        // The audit trail records all four decisions, in order, with their actors.
        JsonNode approvals = json(mockMvc.perform(get("/api/programs/{id}/approvals", programId)
                        .header("Authorization", "Bearer " + facultyToken))
                .andExpect(status().isOk()));

        assertEquals(4, approvals.size());
        assertEquals("submitted", approvals.get(0).get("action").asText());
        assertEquals(1, approvals.get(0).get("stage").asInt());
        assertEquals("noted", approvals.get(1).get("action").asText());
        assertEquals("extension_coordinator", approvals.get(1).get("stageRole").asText());
        assertEquals("recommended", approvals.get(2).get("action").asText());
        assertEquals("campus_extension_coordinator", approvals.get(2).get("stageRole").asText());
        assertEquals("approved", approvals.get(3).get("action").asText());
        assertEquals("campus_admin", approvals.get(3).get("stageRole").asText());

        for (JsonNode approval : approvals) {
            assertNotNull(approval.get("actedBy").asText(null), "every decision records who made it");
            assertNotNull(approval.get("actedAt").asText(null), "every decision records when");
        }
    }

    // --- returns ---

    @Test
    @DisplayName("a return loops back to the faculty and resubmission re-enters at stage 2")
    void returnLoopsBackAndReenteringSkipsStageOne() throws Exception {
        String facultyToken = token(FACULTY_EMAIL, FACULTY_PASSWORD);
        String programId = submittedProposal(facultyToken);

        // Stage 2 notes it on, then stage 3 sends it back.
        stageAction(programId, "review", "{\"action\":\"note\"}", coordinatorToken())
                .andExpect(status().isOk());
        stageAction(programId, "recommend",
                "{\"action\":\"return\",\"comment\":\"Budget breakdown is missing.\"}",
                token(CAMPUS_COORD_EMAIL, CAMPUS_COORD_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("returned"));

        // The faculty can see the reason and edit again.
        JsonNode program = json(mockMvc.perform(get("/api/programs/{id}", programId)
                        .header("Authorization", "Bearer " + facultyToken))
                .andExpect(status().isOk()));
        assertTrue(program.get("canEdit").asBoolean(), "a returned proposal is editable again");

        JsonNode approvals = program.get("approvals");
        JsonNode lastDecision = approvals.get(approvals.size() - 1);
        assertEquals("returned", lastDecision.get("action").asText());
        assertEquals("Budget breakdown is missing.", lastDecision.get("comment").asText());
        assertEquals(3, lastDecision.get("stage").asInt(), "the return is recorded against stage 3");

        mockMvc.perform(patch("/api/programs/{id}", programId)
                        .header("Authorization", "Bearer " + facultyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposalBody("Revised proposal", communityId(facultyToken),
                                programTypeId(facultyToken))))
                .andExpect(status().isOk());

        // Resubmitting lands on 'submitted' — the extension coordinator's queue, i.e. stage 2.
        mockMvc.perform(post("/api/programs/{id}/submit", programId)
                        .header("Authorization", "Bearer " + facultyToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("submitted"));

        // History is appended, never rewritten: the earlier return is still there.
        JsonNode after = json(mockMvc.perform(get("/api/programs/{id}/approvals", programId)
                        .header("Authorization", "Bearer " + facultyToken))
                .andExpect(status().isOk()));
        assertEquals(4, after.size(), "submit, note, return, resubmit");
        assertEquals("returned", after.get(2).get("action").asText());
    }

    @Test
    @DisplayName("a return without a comment is rejected (422)")
    void returnRequiresAComment() throws Exception {
        String programId = submittedProposal(token(FACULTY_EMAIL, FACULTY_PASSWORD));

        stageAction(programId, "review", "{\"action\":\"return\"}", coordinatorToken())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        // The proposal did not move.
        assertEquals("submitted", statusOf(programId, coordinatorToken()));
    }

    // --- the two failure modes that look alike ---

    @Test
    @DisplayName("right role, wrong stage -> 409 (the proposal has moved on)")
    void actingOnTheWrongStageIsAConflict() throws Exception {
        String programId = submittedProposal(token(FACULTY_EMAIL, FACULTY_PASSWORD));

        // Stage 3's owner cannot act while the proposal is still at stage 2.
        stageAction(programId, "recommend", "{\"action\":\"recommend\"}",
                token(CAMPUS_COORD_EMAIL, CAMPUS_COORD_PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));

        // And once it advances, stage 2's owner can no longer act on it either.
        stageAction(programId, "review", "{\"action\":\"note\"}", coordinatorToken())
                .andExpect(status().isOk());
        stageAction(programId, "review", "{\"action\":\"note\"}", coordinatorToken())
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("wrong role for the stage -> 403, including for admin (spec §2.2)")
    void actingWithoutTheStageRoleIsForbidden() throws Exception {
        String programId = submittedProposal(token(FACULTY_EMAIL, FACULTY_PASSWORD));

        // The signatory chain excludes admin by design — this is the check most likely to be
        // "fixed" by mistake, so it is asserted for every stage endpoint.
        String adminToken = token(ADMIN_EMAIL, ADMIN_PASSWORD);
        stageAction(programId, "review", "{\"action\":\"note\"}", adminToken)
                .andExpect(status().isForbidden());
        stageAction(programId, "recommend", "{\"action\":\"recommend\"}", adminToken)
                .andExpect(status().isForbidden());
        stageAction(programId, "approve", "{\"action\":\"approve\"}", adminToken)
                .andExpect(status().isForbidden());

        // Faculty and students hold no stage role at all.
        stageAction(programId, "review", "{\"action\":\"note\"}", token(FACULTY_EMAIL, FACULTY_PASSWORD))
                .andExpect(status().isForbidden());
        stageAction(programId, "approve", "{\"action\":\"approve\"}", token(STUDENT_EMAIL, STUDENT_PASSWORD))
                .andExpect(status().isForbidden());

        // The campus admin holds a stage role but not this stage's — still 409, not 403,
        // because the role is real and only the timing is wrong.
        stageAction(programId, "approve", "{\"action\":\"approve\"}",
                token(CAMPUS_ADMIN_EMAIL, CAMPUS_ADMIN_PASSWORD))
                .andExpect(status().isConflict());

        assertEquals("submitted", statusOf(programId, coordinatorToken()));
    }

    // --- draft vs submit validation ---

    @Test
    @DisplayName("a draft saves with a title alone, but submitting demands the full field set")
    void draftIsPermissiveAndSubmitIsStrict() throws Exception {
        String facultyToken = token(FACULTY_EMAIL, FACULTY_PASSWORD);

        String programId = json(mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + facultyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Barely started %s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isCreated()))
                .get("id").asText();

        mockMvc.perform(post("/api/programs/{id}/submit", programId)
                        .header("Authorization", "Bearer " + facultyToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.communityId").exists())
                .andExpect(jsonPath("$.error.fields.programTypeId").exists())
                .andExpect(jsonPath("$.error.fields.objectives").exists())
                .andExpect(jsonPath("$.error.fields.targetBeneficiaries").exists());

        assertEquals("draft", statusOf(programId, facultyToken), "a failed submit leaves it a draft");
    }

    @Test
    @DisplayName("a title is required even for a draft (422)")
    void titleIsAlwaysRequired() throws Exception {
        mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + token(FACULTY_EMAIL, FACULTY_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  \"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("a proposal with no linked assessment carries a non-blocking warning")
    void missingAssessmentWarnsButDoesNotBlock() throws Exception {
        String facultyToken = token(FACULTY_EMAIL, FACULTY_PASSWORD);
        String programId = completeProposal(facultyToken);

        JsonNode program = json(mockMvc.perform(get("/api/programs/{id}", programId)
                        .header("Authorization", "Bearer " + facultyToken))
                .andExpect(status().isOk()));
        assertEquals(1, program.get("warnings").size());
        assertTrue(program.get("warnings").get(0).asText().contains("needs assessment"));

        // The warning does not prevent submission.
        mockMvc.perform(post("/api/programs/{id}/submit", programId)
                        .header("Authorization", "Bearer " + facultyToken))
                .andExpect(status().isOk());
    }

    // --- editability ---

    @Test
    @DisplayName("a submitted proposal is read-only, even to its owner")
    void submittedProposalsAreReadOnly() throws Exception {
        String facultyToken = token(FACULTY_EMAIL, FACULTY_PASSWORD);
        String programId = submittedProposal(facultyToken);

        mockMvc.perform(patch("/api/programs/{id}", programId)
                        .header("Authorization", "Bearer " + facultyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposalBody("Sneaky edit", communityId(facultyToken),
                                programTypeId(facultyToken))))
                .andExpect(status().isConflict());

        JsonNode program = json(mockMvc.perform(get("/api/programs/{id}", programId)
                        .header("Authorization", "Bearer " + facultyToken))
                .andExpect(status().isOk()));
        assertFalse(program.get("canEdit").asBoolean());
    }

    @Test
    @DisplayName("an edit that omits the faculty lead keeps the existing one")
    void editingDoesNotClearTheFacultyLead() throws Exception {
        String facultyToken = token(FACULTY_EMAIL, FACULTY_PASSWORD);
        String programId = completeProposal(facultyToken);

        String originalLead = json(mockMvc.perform(get("/api/programs/{id}", programId)
                        .header("Authorization", "Bearer " + facultyToken))
                .andExpect(status().isOk()))
                .get("facultyLeadId").asText();
        assertNotNull(originalLead);

        // The body carries no facultyLeadId — a leaderless proposal could never be submitted.
        mockMvc.perform(patch("/api/programs/{id}", programId)
                        .header("Authorization", "Bearer " + facultyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposalBody("Edited title", communityId(facultyToken),
                                programTypeId(facultyToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facultyLeadId").value(originalLead));

        mockMvc.perform(post("/api/programs/{id}/submit", programId)
                        .header("Authorization", "Bearer " + facultyToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a student volunteer cannot create proposals (403)")
    void studentVolunteersCannotCreateProposals() throws Exception {
        mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + token(STUDENT_EMAIL, STUDENT_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Not allowed\"}"))
                .andExpect(status().isForbidden());
    }

    // --- visibility ---

    @Test
    @DisplayName("faculty see only proposals they created or lead; coordinators see all")
    void facultyVisibilityIsScoped() throws Exception {
        String facultyToken = token(FACULTY_EMAIL, FACULTY_PASSWORD);
        String coordinatorToken = coordinatorToken();

        String ownTitle = "Faculty owned " + UUID.randomUUID();
        String foreignTitle = "Coordinator owned " + UUID.randomUUID();
        createProposal(facultyToken, ownTitle);
        String foreignId = createProposal(coordinatorToken, foreignTitle);

        assertTrue(listTitles(facultyToken).contains(ownTitle));
        assertFalse(listTitles(facultyToken).contains(foreignTitle),
                "a faculty member must not see another author's proposal");

        assertTrue(listTitles(coordinatorToken).contains(ownTitle),
                "coordinators see everything");
        assertTrue(listTitles(coordinatorToken).contains(foreignTitle));

        // A foreign proposal reads as not-found rather than forbidden, so its existence does not leak.
        mockMvc.perform(get("/api/programs/{id}", foreignId)
                        .header("Authorization", "Bearer " + facultyToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("status tab counts respect the caller's visibility")
    void statsAreScopedLikeTheList() throws Exception {
        String facultyToken = token(FACULTY_EMAIL, FACULTY_PASSWORD);
        createProposal(facultyToken, "Counted draft " + UUID.randomUUID());

        JsonNode facultyStats = json(mockMvc.perform(get("/api/programs/stats")
                        .header("Authorization", "Bearer " + facultyToken))
                .andExpect(status().isOk()));
        JsonNode coordinatorStats = json(mockMvc.perform(get("/api/programs/stats")
                        .header("Authorization", "Bearer " + coordinatorToken()))
                .andExpect(status().isOk()));

        assertTrue(facultyStats.get("draft").asLong() >= 1);
        assertTrue(coordinatorStats.get("total").asLong() >= facultyStats.get("total").asLong(),
                "a coordinator's totals include everything the faculty can see");
    }

    @Test
    @DisplayName("the under_review tab collapses the three in-chain statuses")
    void underReviewTabSpansTheChain() throws Exception {
        String facultyToken = token(FACULTY_EMAIL, FACULTY_PASSWORD);
        String programId = submittedProposal(facultyToken);

        JsonNode list = json(mockMvc.perform(get("/api/programs?status=under_review&perPage=100")
                        .header("Authorization", "Bearer " + coordinatorToken()))
                .andExpect(status().isOk()));
        assertTrue(containsId(list.get("data"), programId), "a submitted proposal is under review");

        stageAction(programId, "review", "{\"action\":\"note\"}", coordinatorToken())
                .andExpect(status().isOk());

        list = json(mockMvc.perform(get("/api/programs?status=under_review&perPage=100")
                        .header("Authorization", "Bearer " + coordinatorToken()))
                .andExpect(status().isOk()));
        assertTrue(containsId(list.get("data"), programId), "and still is once noted onward");
    }

    // --- available actions (what the frontend renders from) ---

    @Test
    @DisplayName("availableActions reflects the caller's role and the current stage")
    void availableActionsAreServerDriven() throws Exception {
        String facultyToken = token(FACULTY_EMAIL, FACULTY_PASSWORD);
        String programId = completeProposal(facultyToken);

        assertTrue(actionsFor(programId, facultyToken).contains("submit"));
        assertFalse(actionsFor(programId, coordinatorToken()).contains("note"),
                "nothing to note while it is still a draft");

        mockMvc.perform(post("/api/programs/{id}/submit", programId)
                        .header("Authorization", "Bearer " + facultyToken))
                .andExpect(status().isOk());

        assertTrue(actionsFor(programId, coordinatorToken()).contains("note"));
        assertTrue(actionsFor(programId, coordinatorToken()).contains("return"));
        assertFalse(actionsFor(programId, facultyToken).contains("submit"),
                "the owner has nothing to do while it is under review");
        assertFalse(actionsFor(programId, token(CAMPUS_ADMIN_EMAIL, CAMPUS_ADMIN_PASSWORD)).contains("approve"),
                "the campus admin's turn has not come yet");
    }

    // --- the two Phase-1 stubs this module closes ---

    @Test
    @DisplayName("a community with a live program cannot be deleted (409), and appears in its history")
    void communityDeleteIsBlockedByLivePrograms() throws Exception {
        String adminToken = token(ADMIN_EMAIL, ADMIN_PASSWORD);
        String coordinatorToken = coordinatorToken();

        String communityId = json(mockMvc.perform(post("/api/communities")
                        .header("Authorization", "Bearer " + coordinatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Deletable Barangay %s","municipality":"Indang","province":"Cavite",
                                 "classification":"rural"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated()))
                .get("id").asText();

        // With no programs it deletes cleanly — proving the block is about programs, not permissions.
        String throwaway = json(mockMvc.perform(post("/api/communities")
                        .header("Authorization", "Bearer " + coordinatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Empty Barangay %s","municipality":"Indang","province":"Cavite",
                                 "classification":"rural"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated()))
                .get("id").asText();
        mockMvc.perform(delete("/api/communities/{id}", throwaway)
                        .header("Authorization", "Bearer " + coordinatorToken))
                .andExpect(status().isOk());

        String programId = json(mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + coordinatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposalBody("Livelihood drive " + UUID.randomUUID(),
                                communityId, programTypeId(coordinatorToken))))
                .andExpect(status().isCreated()))
                .get("id").asText();

        mockMvc.perform(delete("/api/communities/{id}", communityId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));

        // The Phase-1 history stub now returns the real program.
        JsonNode history = json(mockMvc.perform(get("/api/communities/{id}/history", communityId)
                        .header("Authorization", "Bearer " + coordinatorToken))
                .andExpect(status().isOk()));
        assertEquals(1, history.size());
        assertEquals(programId, history.get(0).get("programId").asText());
        assertEquals("draft", history.get(0).get("status").asText());

        // Cancelling it releases the block.
        mockMvc.perform(delete("/api/programs/{id}", programId)
                        .header("Authorization", "Bearer " + coordinatorToken))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/communities/{id}", communityId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the community list reports its active program count")
    void communityListCountsActivePrograms() throws Exception {
        String coordinatorToken = coordinatorToken();
        String communityId = communityId(coordinatorToken);

        createProposalFor(coordinatorToken, "Counted program " + UUID.randomUUID(), communityId);

        JsonNode list = json(mockMvc.perform(get("/api/communities?perPage=100")
                        .header("Authorization", "Bearer " + coordinatorToken))
                .andExpect(status().isOk()));

        for (JsonNode row : list.get("data")) {
            if (row.get("id").asText().equals(communityId)) {
                assertTrue(row.get("activeProgramCount").asInt() >= 1,
                        "the count is no longer hardcoded to zero");
                return;
            }
        }
        throw new AssertionError("target community missing from the list");
    }

    // --- provenance: Module 4 -> Module 5 ---

    @Test
    @DisplayName("accepting a recommendation spawns a pre-filled draft proposal linked back to it")
    void acceptingARecommendationCreatesADraftProposal() throws Exception {
        String coordinatorToken = coordinatorToken();
        JsonNode recommendation = topRecommendation(coordinatorToken);
        String recommendationId = recommendation.get("id").asText();
        String programTypeName = recommendation.get("programTypeName").asText();
        String surveyId = recommendation.get("surveyId").asText();

        JsonNode decision = json(mockMvc.perform(post("/api/recommendations/{id}/accept", recommendationId)
                        .header("Authorization", "Bearer " + coordinatorToken))
                .andExpect(status().isOk()));

        String programId = decision.get("spawnedProgramId").asText();
        assertNotNull(programId, "accepting produces a proposal to work from");

        JsonNode program = json(mockMvc.perform(get("/api/programs/{id}", programId)
                        .header("Authorization", "Bearer " + coordinatorToken))
                .andExpect(status().isOk()));

        assertEquals("draft", program.get("status").asText(), "it arrives as an editable draft");
        assertEquals(recommendationId, program.get("recommendationId").asText(),
                "provenance points back at the recommendation");
        assertEquals(surveyId, program.get("surveyId").asText(),
                "and at the assessment that justifies it");
        assertEquals(programTypeName, program.get("programTypeName").asText());
        assertTrue(program.get("title").asText().startsWith(programTypeName),
                "the title is pre-filled from the program type");
        assertEquals(0, program.get("warnings").size(),
                "a proposal born from an assessment raises no missing-assessment warning");

        // Rejecting, by contrast, creates nothing.
        JsonNode another = topRecommendation(coordinatorToken);
        JsonNode rejection = json(mockMvc.perform(
                        post("/api/recommendations/{id}/reject", another.get("id").asText())
                                .header("Authorization", "Bearer " + coordinatorToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"note\":\"Not a priority this semester.\"}"))
                .andExpect(status().isOk()));
        assertTrue(rejection.get("spawnedProgramId").isNull(),
                "a rejected recommendation spawns no proposal");
    }

    /** Builds a finalized survey, generates recommendations, and returns the top-ranked one. */
    private JsonNode topRecommendation(String token) throws Exception {
        String communityId = communityId(token);
        String health = needCategoryId(token, "Health");

        String surveyId = json(mockMvc.perform(post("/api/surveys")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"communityId":"%s","title":"Provenance fixture %s"}
                                """.formatted(communityId, UUID.randomUUID())))
                .andExpect(status().isCreated()))
                .get("id").asText();

        String questionId = json(mockMvc.perform(post("/api/surveys/{id}/questions", surveyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"questionText":"Rate health access","questionType":"rating","weight":2.0,
                                 "required":true,"needCategoryId":"%s"}
                                """.formatted(health)))
                .andExpect(status().isCreated()))
                .get("id").asText();

        String accessToken = json(mockMvc.perform(post("/api/surveys/{id}/deploy", surveyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()))
                .get("accessToken").asText();

        mockMvc.perform(post("/api/public/surveys/{token}/responses", accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"respondentSex":"female","respondentToken":"prov-%s","consent":true,
                                 "answers":[{"questionId":"%s","value":"5"}]}
                                """.formatted(UUID.randomUUID(), questionId)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/surveys/{id}/finalize", surveyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        return json(mockMvc.perform(post("/api/surveys/{id}/recommendations/generate", surveyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()))
                .get(0);
    }

    private String needCategoryId(String token, String name) throws Exception {
        JsonNode categories = json(mockMvc.perform(get("/api/need-categories")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()));
        for (JsonNode category : categories) {
            if (category.get("name").asText().equals(name)) {
                return category.get("id").asText();
            }
        }
        throw new IllegalStateException("Seeded need category not found: " + name);
    }

    // --- helpers ---

    private String completeProposal(String token) throws Exception {
        return createProposalFor(token, "Community proposal " + UUID.randomUUID(), communityId(token));
    }

    private String submittedProposal(String token) throws Exception {
        String programId = completeProposal(token);
        mockMvc.perform(post("/api/programs/{id}/submit", programId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return programId;
    }

    private String createProposal(String token, String title) throws Exception {
        return createProposalFor(token, title, communityId(token));
    }

    private String createProposalFor(String token, String title, String communityId) throws Exception {
        return json(mockMvc.perform(post("/api/programs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposalBody(title, communityId, programTypeId(token))))
                .andExpect(status().isCreated()))
                .get("id").asText();
    }

    /** A fully-populated proposal body — everything {@code submit} validates. */
    private String proposalBody(String title, String communityId, String programTypeId) {
        return """
                {"title":"%s","communityId":"%s","programTypeId":"%s",
                 "objectives":"Improve household health outcomes in the partner barangay.",
                 "targetBeneficiaries":120,"proposedDate":"2026-09-01","endDate":"2026-09-30",
                 "venue":"Barangay Hall","budgetRequested":50000}
                """.formatted(title, communityId, programTypeId);
    }

    private ResultActions stageAction(String programId, String endpoint, String body, String token)
            throws Exception {
        return mockMvc.perform(post("/api/programs/{id}/" + endpoint, programId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String statusOf(String programId, String token) throws Exception {
        return json(mockMvc.perform(get("/api/programs/{id}", programId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()))
                .get("status").asText();
    }

    private java.util.List<String> actionsFor(String programId, String token) throws Exception {
        JsonNode actions = json(mockMvc.perform(get("/api/programs/{id}", programId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()))
                .get("availableActions");
        java.util.List<String> result = new java.util.ArrayList<>();
        actions.forEach(node -> result.add(node.asText()));
        return result;
    }

    private java.util.List<String> listTitles(String token) throws Exception {
        JsonNode list = json(mockMvc.perform(get("/api/programs?perPage=100")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()));
        java.util.List<String> titles = new java.util.ArrayList<>();
        list.get("data").forEach(row -> titles.add(row.get("title").asText()));
        return titles;
    }

    private boolean containsId(JsonNode rows, String programId) {
        for (JsonNode row : rows) {
            if (row.get("id").asText().equals(programId)) {
                return true;
            }
        }
        return false;
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
