package com.cems.api;

import com.cems.api.service.EmailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module 4 coverage: the finalize gate, ranking, the immutability of decided rows across a
 * regeneration, the decision rules, the §2.2 role matrix, and the program-type/matrix admin surface.
 *
 * <p>The scoring maths itself is covered by {@code RecommendationScoringServiceTest} (the
 * spec-mandated fixture); here it is asserted end-to-end through the API — including that every
 * {@code matchScore} still recomputes from its stored {@code score_breakdown} (AC 5).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendationControllerIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@cems.com";
    private static final String ADMIN_PASSWORD = "Admin123!";
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

    // --- the finalize gate ---

    @Test
    void generatingBeforeResultsAreFinalizedIsRejected() throws Exception {
        String token = coordinatorToken();
        String surveyId = surveyWithResponses(token);

        mockMvc.perform(post("/api/surveys/{id}/recommendations/generate", surveyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));

        // Nothing was written for the survey either.
        mockMvc.perform(get("/api/surveys/{id}/recommendations", surveyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // --- generation ---

    @Test
    void generateRanksSeededProgramTypesAndStoresAReproducibleBreakdown() throws Exception {
        String token = coordinatorToken();
        String surveyId = finalizedSurvey(token);

        JsonNode recommendations = generate(surveyId, token);

        assertEquals(5, recommendations.size(), "all five seeded program types are scored");

        // Health is the top need in the fixture, so the health-focused type must lead.
        assertEquals("Health & Wellness Caravan", recommendations.get(0).get("programTypeName").asText());
        assertEquals(1, recommendations.get(0).get("rank").asInt());
        assertEquals("pending", recommendations.get(0).get("status").asText());

        // Ranks are dense and ordered, and scores never increase down the list.
        BigDecimal previous = null;
        for (int index = 0; index < recommendations.size(); index++) {
            JsonNode row = recommendations.get(index);
            assertEquals(index + 1, row.get("rank").asInt(), "ranks are 1..n in list order");

            BigDecimal score = new BigDecimal(row.get("matchScore").asText());
            assertTrue(score.compareTo(BigDecimal.ZERO) >= 0 && score.compareTo(new BigDecimal("100")) <= 0,
                    "match score stays within 0-100");
            if (previous != null) {
                assertTrue(previous.compareTo(score) >= 0, "list is ordered by descending score");
            }
            previous = score;

            assertBreakdownReproducesScore(row);
        }
    }

    /** AC 5: the displayed score must be derivable from the stored breakdown, not just plausible. */
    private void assertBreakdownReproducesScore(JsonNode recommendation) {
        JsonNode breakdown = recommendation.get("breakdown");
        assertNotNull(breakdown, "every recommendation carries its explanation");

        BigDecimal summed = BigDecimal.ZERO;
        for (JsonNode category : breakdown.get("categories")) {
            BigDecimal avg = new BigDecimal(category.get("avgScore").asText());
            BigDecimal weight = new BigDecimal(category.get("weight").asText());
            BigDecimal multiplier = new BigDecimal(category.get("multiplier").asText());
            BigDecimal contribution = new BigDecimal(category.get("contribution").asText());

            assertEquals(0, avg.multiply(weight).multiply(multiplier)
                            .setScale(2, java.math.RoundingMode.HALF_UP).compareTo(contribution),
                    "contribution = avg x weight x multiplier");
            summed = summed.add(contribution);
        }

        BigDecimal raw = new BigDecimal(breakdown.get("rawScore").asText());
        assertEquals(0, summed.compareTo(raw), "contributions add up to the raw score");

        BigDecimal max = new BigDecimal(breakdown.get("maxTheoretical").asText());
        BigDecimal bonus = new BigDecimal(breakdown.get("sectorBonus").asText());
        BigDecimal expected = max.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : raw.add(bonus).multiply(new BigDecimal("100"))
                        .divide(max, 2, java.math.RoundingMode.HALF_UP)
                        .min(new BigDecimal("100.00"));

        assertEquals(0, expected.compareTo(new BigDecimal(recommendation.get("matchScore").asText())),
                "match score recomputes from the breakdown");
    }

    @Test
    void regeneratingReplacesPendingRowsButKeepsDecidedOnes() throws Exception {
        String token = coordinatorToken();
        String surveyId = finalizedSurvey(token);

        JsonNode first = generate(surveyId, token);
        String acceptedId = first.get(0).get("id").asText();
        String rejectedId = first.get(1).get("id").asText();

        decide(acceptedId, "accept", "{\"note\":\"Fits the barangay health plan.\"}", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.decidedByName").isNotEmpty())
                .andExpect(jsonPath("$.decidedAt").isNotEmpty());
        decide(rejectedId, "reject", "{\"note\":\"No budget this semester.\"}", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("rejected"));

        JsonNode second = generate(surveyId, token);

        // 5 fresh pending rows + the 2 decided ones that were kept.
        assertEquals(7, second.size());
        long decided = 0;
        boolean acceptedSurvived = false;
        boolean rejectedSurvived = false;
        for (JsonNode row : second) {
            if (!"pending".equals(row.get("status").asText())) {
                decided++;
            }
            acceptedSurvived |= row.get("id").asText().equals(acceptedId);
            rejectedSurvived |= row.get("id").asText().equals(rejectedId);
        }
        assertEquals(2, decided, "decided rows are audit history and survive a regeneration");
        assertTrue(acceptedSurvived && rejectedSurvived, "the same rows, not replacements");
    }

    // --- decision rules ---

    @Test
    void rejectingWithoutAReasonIsUnprocessable() throws Exception {
        String token = coordinatorToken();
        String recommendationId = firstRecommendationId(token);

        decide(recommendationId, "reject", "{\"note\":\"   \"}", token)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value(
                        "A reason is required when rejecting a recommendation."));

        // Accepting, by contrast, needs no note.
        decide(recommendationId, "accept", null, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));
    }

    @Test
    void decidingAnAlreadyDecidedRecommendationIsAConflict() throws Exception {
        String token = coordinatorToken();
        String recommendationId = firstRecommendationId(token);

        decide(recommendationId, "modify", "{\"note\":\"Shorten to one day.\"}", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("modified"));

        decide(recommendationId, "accept", null, token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    // --- the §2.2 role matrix ---

    @Test
    void facultyMayViewRecommendationsButNotGenerateOrDecide() throws Exception {
        String coordinator = coordinatorToken();
        String surveyId = finalizedSurvey(coordinator);
        String recommendationId = generate(surveyId, coordinator).get(0).get("id").asText();

        String faculty = token(FACULTY_EMAIL, FACULTY_PASSWORD);

        mockMvc.perform(get("/api/surveys/{id}/recommendations", surveyId)
                        .header("Authorization", "Bearer " + faculty))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));

        mockMvc.perform(post("/api/surveys/{id}/recommendations/generate", surveyId)
                        .header("Authorization", "Bearer " + faculty))
                .andExpect(status().isForbidden());

        decide(recommendationId, "accept", null, faculty).andExpect(status().isForbidden());
    }

    @Test
    void studentVolunteersCannotSeeRecommendationsAtAll() throws Exception {
        String surveyId = finalizedSurvey(coordinatorToken());
        String student = token(STUDENT_EMAIL, STUDENT_PASSWORD);

        mockMvc.perform(get("/api/surveys/{id}/recommendations", surveyId)
                        .header("Authorization", "Bearer " + student))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/program-types").header("Authorization", "Bearer " + student))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyMatrixConfigurersMayChangeTheLibraryOrTheMatrix() throws Exception {
        // The plain extension coordinator can decide recommendations but not configure the matrix.
        String coordinator = coordinatorToken();

        mockMvc.perform(get("/api/scoring-matrix").header("Authorization", "Bearer " + coordinator))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/program-types")
                        .header("Authorization", "Bearer " + coordinator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Should Not Exist\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/scoring-matrix")
                        .header("Authorization", "Bearer " + coordinator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cells\":[]}"))
                .andExpect(status().isForbidden());
    }

    // --- program-type library ---

    @Test
    void programTypesCanBeCreatedRenamedAndRetired() throws Exception {
        String token = token(CAMPUS_COORD_EMAIL, CAMPUS_COORD_PASSWORD);

        String id = objectMapper.readTree(mockMvc.perform(post("/api/program-types")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Digital Literacy Program","description":"Basic computer skills.",
                                 "defaultDuration":"6 weeks","active":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/program-types")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"digital literacy program\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(patch("/api/program-types/{id}", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Digital Literacy Program\",\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        // A retired type is excluded from future scoring runs.
        String surveyId = finalizedSurvey(coordinatorToken());
        JsonNode recommendations = generate(surveyId, coordinatorToken());
        for (JsonNode row : recommendations) {
            assertTrue(!row.get("programTypeId").asText().equals(id),
                    "inactive program types are not scored");
        }
    }

    // --- the scoring matrix ---

    @Test
    void matrixEditsAffectFutureRunsOnlyAndAreRangeChecked() throws Exception {
        String coordinator = coordinatorToken();
        String admin = token(ADMIN_EMAIL, ADMIN_PASSWORD);
        String surveyId = finalizedSurvey(coordinator);

        JsonNode before = generate(surveyId, coordinator);
        String topId = before.get(0).get("id").asText();
        String topScore = before.get(0).get("matchScore").asText();
        String healthCaravanId = before.get(0).get("programTypeId").asText();

        // Decide it, so it is retained through the next run and we can compare like for like.
        decide(topId, "accept", null, coordinator).andExpect(status().isOk());

        // Out-of-range weights are rejected before anything is written.
        mockMvc.perform(put("/api/scoring-matrix")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cells":[{"programTypeId":"%s","needCategoryId":"%s","weight":9.0}]}
                                """.formatted(healthCaravanId, needCategoryId(admin, "Health"))))
                .andExpect(status().isUnprocessableEntity());

        // Zero the health weight — the health-focused type should stop leading afterwards.
        mockMvc.perform(put("/api/scoring-matrix")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cells":[{"programTypeId":"%s","needCategoryId":"%s","weight":0.0}]}
                                """.formatted(healthCaravanId, needCategoryId(admin, "Health"))))
                .andExpect(status().isOk());

        JsonNode after = generate(surveyId, coordinator);

        // The already-decided row keeps its original score: edits are not retroactive.
        // Scores are compared numerically — the stored scale can come back as 0.0 rather than 0.00.
        boolean found = false;
        for (JsonNode row : after) {
            if (row.get("id").asText().equals(topId)) {
                assertEquals(0, new BigDecimal(topScore)
                                .compareTo(new BigDecimal(row.get("matchScore").asText())),
                        "a decided recommendation keeps the score it was generated with");
                found = true;
            }
        }
        assertTrue(found, "the decided row survived");

        // ...while the fresh run reflects the new matrix.
        for (JsonNode row : after) {
            if ("pending".equals(row.get("status").asText())
                    && row.get("programTypeId").asText().equals(healthCaravanId)) {
                assertEquals(0, BigDecimal.ZERO
                                .compareTo(new BigDecimal(row.get("matchScore").asText())),
                        "with its only weight zeroed the type no longer matches anything");
            }
        }
    }

    // --- fixture ---

    private JsonNode generate(String surveyId, String token) throws Exception {
        return objectMapper.readTree(mockMvc.perform(
                        post("/api/surveys/{id}/recommendations/generate", surveyId)
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions decide(
            String recommendationId, String action, String body, String token) throws Exception {
        var request = post("/api/recommendations/{id}/" + action, recommendationId)
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private String firstRecommendationId(String token) throws Exception {
        return generate(finalizedSurvey(token), token).get(0).get("id").asText();
    }

    /** A survey whose results are finalized: Health scores highest, Education lowest. */
    private String finalizedSurvey(String token) throws Exception {
        String surveyId = surveyWithResponses(token);
        mockMvc.perform(post("/api/surveys/{id}/finalize", surveyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return surveyId;
    }

    /**
     * Deploys a survey and records three responses (2 female, 1 male):
     * Health (weight 2.0): 5, 3, 4  -> avg 4.00, high
     * Education (weight 1.0): 1, 2, 1 -> avg 1.33, low
     */
    private String surveyWithResponses(String token) throws Exception {
        String communityId = firstCommunityId(token);
        String health = needCategoryId(token, "Health");
        String education = needCategoryId(token, "Education");

        String surveyId = objectMapper.readTree(mockMvc.perform(post("/api/surveys")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"communityId":"%s","title":"Recommendation Fixture %s"}
                                """.formatted(communityId, java.util.UUID.randomUUID())))
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

        String accessToken = objectMapper.readTree(mockMvc.perform(
                        post("/api/surveys/{id}/deploy", surveyId)
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("accessToken").asText();

        submit(accessToken, "female", "rec-f1", healthQ, "5", educationQ, "1");
        submit(accessToken, "female", "rec-f2", healthQ, "3", educationQ, "2");
        submit(accessToken, "male", "rec-m1", healthQ, "4", educationQ, "1");

        return surveyId;
    }

    private void submit(String accessToken, String sex, String device,
            String healthQ, String healthValue,
            String educationQ, String educationValue) throws Exception {
        mockMvc.perform(post("/api/public/surveys/{token}/responses", accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"respondentSex":"%s","respondentToken":"%s","consent":true,
                                 "answers":[{"questionId":"%s","value":"%s"},
                                            {"questionId":"%s","value":"%s"}]}
                                """.formatted(sex, device, healthQ, healthValue,
                                educationQ, educationValue)))
                .andExpect(status().isCreated());
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
