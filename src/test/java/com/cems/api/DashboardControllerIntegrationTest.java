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

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Coverage of the landing dashboard's aggregate read.
 *
 * <p>The test database is shared across the suite, so every figure here is asserted as a
 * <em>delta</em> against a baseline captured in the same test — an absolute total would break the
 * moment another test class seeds a row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardControllerIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@cems.com";
    private static final String ADMIN_PASSWORD = "Admin123!";
    private static final String COORDINATOR_EMAIL = "coordinator@cems.com";
    private static final String COORDINATOR_PASSWORD = "Coordinator123!";
    private static final String FACULTY_EMAIL = "faculty@cems.com";
    private static final String FACULTY_PASSWORD = "Faculty123!";
    private static final String STUDENT_EMAIL = "student@cems.com";
    private static final String STUDENT_PASSWORD = "Student123!";

    private static final Set<String> PRIORITIES = Set.of("critical", "high", "moderate", "low");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmailService emailService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- access ---

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/dashboard/overview"))
                .andExpect(status().isUnauthorized());
    }

    /** The dashboard is the post-login landing screen, so the narrowest role must still load it. */
    @Test
    void studentVolunteerCanReadTheOverviewWithEverySection() throws Exception {
        mockMvc.perform(get("/api/dashboard/overview")
                        .header("Authorization", "Bearer " + token(STUDENT_EMAIL, STUDENT_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.communities.total").isNumber())
                .andExpect(jsonPath("$.assessments.responses").isNumber())
                .andExpect(jsonPath("$.recommendations.total").isNumber())
                .andExpect(jsonPath("$.programs.total").isNumber())
                .andExpect(jsonPath("$.topNeeds").isArray())
                .andExpect(jsonPath("$.generatedAt").exists());
    }

    /** The audit trail is admin-only (spec §2.2); non-admins get the field omitted, not an empty list. */
    @Test
    void activityFeedIsAttachedForAdminsOnly() throws Exception {
        JsonNode forAdmin = overview(token(ADMIN_EMAIL, ADMIN_PASSWORD));
        assertTrue(forAdmin.get("recentActivity").isArray(), "admin should receive the activity feed");

        JsonNode forFaculty = overview(token(FACULTY_EMAIL, FACULTY_PASSWORD));
        assertTrue(forFaculty.get("recentActivity").isNull(),
                "a non-admin must not receive audit rows, not even an empty array");
    }

    // --- community aggregates ---

    @Test
    void communityProfileTotalsTrackNewProfiles() throws Exception {
        String token = token(ADMIN_EMAIL, ADMIN_PASSWORD);
        JsonNode before = overview(token).get("communities");

        createCommunity(token, """
                {"name":"Dashboard Delta A","municipality":"Bacoor","province":"Cavite",
                 "classification":"urban","estimatedPopulation":900,"householdCount":200,
                 "populationFemale":500,"populationMale":400,"sectorIds":[]}
                """);

        JsonNode after = overview(token).get("communities");
        assertEquals(1, delta(before, after, "total"));
        assertEquals(900, delta(before, after, "population"));
        assertEquals(200, delta(before, after, "households"));
        assertEquals(500, delta(before, after, "populationFemale"));
        assertEquals(400, delta(before, after, "populationMale"));
        assertEquals(1, delta(before, after, "withSexSplit"));
    }

    /**
     * A profile with no female/male figures still counts as a community, but must not inflate the
     * denominator the dashboard prints beside the split — otherwise the UI would claim GAD coverage
     * it does not have.
     */
    @Test
    void profileWithoutASexSplitIsExcludedFromTheSplitDenominator() throws Exception {
        String token = token(ADMIN_EMAIL, ADMIN_PASSWORD);
        JsonNode before = overview(token).get("communities");

        createCommunity(token, """
                {"name":"Dashboard Delta B","municipality":"Bacoor","province":"Cavite",
                 "classification":"rural","estimatedPopulation":300,"householdCount":75,
                 "sectorIds":[]}
                """);

        JsonNode after = overview(token).get("communities");
        assertEquals(1, delta(before, after, "total"));
        assertEquals(300, delta(before, after, "population"));
        assertEquals(0, delta(before, after, "withSexSplit"));
        assertEquals(0, delta(before, after, "populationFemale"));
        assertEquals(0, delta(before, after, "populationMale"));
    }

    // --- assessment aggregates ---

    @Test
    void respondentCountsAreSexDisaggregated() throws Exception {
        String token = token(COORDINATOR_EMAIL, COORDINATOR_PASSWORD);
        JsonNode before = overview(token).get("assessments");

        deployedSurveyWithThreeResponses(token);

        JsonNode after = overview(token).get("assessments");
        assertEquals(1, delta(before, after, "surveys"));
        assertEquals(1, delta(before, after, "deployed"));
        assertEquals(3, delta(before, after, "responses"));
        assertEquals(2, delta(before, after, "responsesFemale"));
        assertEquals(1, delta(before, after, "responsesMale"));
        assertEquals(0, delta(before, after, "responsesUndisclosed"));
    }

    /**
     * Finalizing snapshots the category scores; the dashboard must pick them up, rank them
     * strongest-first, and label each with the same priority thresholds the results screen uses.
     */
    @Test
    void finalizedAssessmentFeedsTheFinalizedCountAndTopNeeds() throws Exception {
        String token = token(COORDINATOR_EMAIL, COORDINATOR_PASSWORD);
        JsonNode before = overview(token).get("assessments");

        String surveyId = deployedSurveyWithThreeResponses(token);
        mockMvc.perform(post("/api/surveys/{id}/finalize", surveyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        JsonNode overview = overview(token);
        assertEquals(1, delta(before, overview.get("assessments"), "finalized"));

        JsonNode topNeeds = overview.get("topNeeds");
        assertFalse(topNeeds.isEmpty(), "a finalized assessment should surface its need categories");
        assertTrue(topNeeds.size() <= 5, "the panel shows at most five rows");

        double previousScore = Double.MAX_VALUE;
        for (JsonNode need : topNeeds) {
            assertNotNull(need.get("needCategoryName").asText());
            assertTrue(need.get("assessmentCount").asLong() >= 1);
            assertTrue(PRIORITIES.contains(need.get("priority").asText()),
                    "unexpected priority: " + need.get("priority").asText());

            double score = need.get("avgScore").asDouble();
            assertTrue(score <= previousScore, "top needs must be ordered strongest-first");
            previousScore = score;

            // GAD: the sexes of the respondents behind a category never exceed its response count.
            assertTrue(need.get("femaleCount").asLong() + need.get("maleCount").asLong()
                    <= need.get("responseCount").asLong());
        }
    }

    // --- helpers ---

    private JsonNode overview(String token) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/dashboard/overview")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static long delta(JsonNode before, JsonNode after, String field) {
        return after.get(field).asLong() - before.get(field).asLong();
    }

    private void createCommunity(String token, String payload) throws Exception {
        mockMvc.perform(post("/api/communities")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());
    }

    /** Deploys a one-question survey and records three responses: 2 female, 1 male. */
    private String deployedSurveyWithThreeResponses(String token) throws Exception {
        String communityId = firstCommunityId(token);
        String surveyId = objectMapper.readTree(mockMvc.perform(post("/api/surveys")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"communityId":"%s","title":"Dashboard Fixture Survey"}
                                """.formatted(communityId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        String questionId = objectMapper.readTree(mockMvc.perform(post("/api/surveys/{id}/questions", surveyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"questionText":"Rate health access","questionType":"rating","weight":2.0,
                                 "required":true,"needCategoryId":"%s"}
                                """.formatted(needCategoryId(token, "Health"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        String accessToken = objectMapper.readTree(mockMvc.perform(post("/api/surveys/{id}/deploy", surveyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("accessToken").asText();

        // Device tokens must be unique per survey (dedupe), so scope them to this survey.
        List<String> sexes = List.of("female", "female", "male");
        for (int index = 0; index < sexes.size(); index += 1) {
            mockMvc.perform(post("/api/public/surveys/{token}/responses", accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"respondentSex":"%s","respondentToken":"dash-%s-%d","consent":true,
                                     "answers":[{"questionId":"%s","value":"4"}]}
                                    """.formatted(sexes.get(index), surveyId, index, questionId)))
                    .andExpect(status().isCreated());
        }

        return surveyId;
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

    private String token(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }
}
