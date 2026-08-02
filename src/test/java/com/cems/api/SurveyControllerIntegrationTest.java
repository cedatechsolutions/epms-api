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
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pass A coverage of Module 3 (spec §Module 3 §1): survey builder CRUD, question management with
 * weight/type/option validation, the deploy gate (≥1 question → access token), question lock after
 * deployment, close lifecycle, soft delete, and the permission matrix (faculty own-only; student
 * volunteers excluded entirely).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SurveyControllerIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@cems.com";
    private static final String ADMIN_PASSWORD = "Admin123!";
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

    // --- lookup ---

    @Test
    void needCategoriesAreSeeded() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/need-categories")
                        .header("Authorization", "Bearer " + coordinatorToken()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode categories = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(7, categories.size(), "migration V5 seeds 7 need categories");
    }

    // --- CRUD ---

    @Test
    void coordinatorCanCreateUpdateAndListSurvey() throws Exception {
        String token = coordinatorToken();
        String communityId = firstCommunityId(token);

        JsonNode created = createSurvey(token, communityId, "Health Needs 2026");
        String id = created.get("id").asText();
        assertEquals("draft", created.get("status").asText());
        assertTrue(created.get("accessToken").isNull(), "a draft has no public token yet");

        mockMvc.perform(patch("/api/surveys/{id}", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"communityId":"%s","title":"Health Needs 2026 (rev)","targetResponses":30}
                                """.formatted(communityId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Health Needs 2026 (rev)"))
                .andExpect(jsonPath("$.targetResponses").value(30));

        MvcResult list = mockMvc.perform(get("/api/surveys")
                        .header("Authorization", "Bearer " + token)
                        .param("search", "rev"))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(list.getResponse().getContentAsString().contains("Health Needs 2026 (rev)"));
    }

    @Test
    void softDeleteHidesSurvey() throws Exception {
        String token = coordinatorToken();
        String id = createSurvey(token, firstCommunityId(token), "To Delete").get("id").asText();

        mockMvc.perform(delete("/api/surveys/{id}", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/surveys/{id}", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // --- questions + validation ---

    @Test
    void questionValidationRejectsBadWeightTypeAndMissingOptions() throws Exception {
        String token = coordinatorToken();
        String id = createSurvey(token, firstCommunityId(token), "Validation Survey").get("id").asText();

        // weight above the 0.5–5.0 range
        mockMvc.perform(addQuestion(id, token, """
                        {"questionText":"Too heavy","questionType":"rating","weight":9.0}
                        """))
                .andExpect(status().isUnprocessableEntity());

        // unknown question type
        mockMvc.perform(addQuestion(id, token, """
                        {"questionText":"Bad type","questionType":"telepathy","weight":1.0}
                        """))
                .andExpect(status().isUnprocessableEntity());

        // choice type with fewer than two options
        mockMvc.perform(addQuestion(id, token, """
                        {"questionText":"Pick one","questionType":"multiple_choice","weight":1.0,
                         "options":[{"label":"Only","value":"only"}]}
                        """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void questionsAreOrderedAndCarryCategoryAndWeight() throws Exception {
        String token = coordinatorToken();
        String categoryId = firstNeedCategoryId(token);
        String id = createSurvey(token, firstCommunityId(token), "Ordered Survey").get("id").asText();

        mockMvc.perform(addQuestion(id, token, """
                        {"questionText":"Rate clinic access","questionType":"rating","weight":2.5,
                         "needCategoryId":"%s"}
                        """.formatted(categoryId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderIndex").value(0))
                .andExpect(jsonPath("$.weight").value(2.5))
                .andExpect(jsonPath("$.needCategoryId").value(categoryId));

        mockMvc.perform(addQuestion(id, token, """
                        {"questionText":"Which services?","questionType":"checkbox","weight":1.0,
                         "options":[{"label":"Clinic","value":"clinic"},{"label":"Pharmacy","value":"pharmacy"}]}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderIndex").value(1))
                .andExpect(jsonPath("$.options[1].label").value("Pharmacy"));

        mockMvc.perform(get("/api/surveys/{id}", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionCount").value(2))
                .andExpect(jsonPath("$.questions[0].questionText").value("Rate clinic access"));
    }

    // --- deploy / close lifecycle ---

    @Test
    void deployRequiresAtLeastOneQuestionThenIssuesAccessTokenAndLocksQuestions() throws Exception {
        String token = coordinatorToken();
        String id = createSurvey(token, firstCommunityId(token), "Deploy Survey").get("id").asText();

        // AC: cannot deploy with zero questions.
        mockMvc.perform(post("/api/surveys/{id}/deploy", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));

        mockMvc.perform(addQuestion(id, token, """
                        {"questionText":"Rate water supply","questionType":"rating","weight":3.0}
                        """))
                .andExpect(status().isCreated());

        MvcResult deployed = mockMvc.perform(post("/api/surveys/{id}/deploy", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("deployed"))
                .andReturn();
        String accessToken = objectMapper.readTree(deployed.getResponse().getContentAsString())
                .get("accessToken").asText();
        assertFalse(accessToken.isBlank(), "deploying issues a public access token");

        // Deployed surveys are question-locked (no versioning in v1).
        mockMvc.perform(addQuestion(id, token, """
                        {"questionText":"Late addition","questionType":"rating","weight":1.0}
                        """))
                .andExpect(status().isConflict());

        // Re-deploying is invalid; closing a deployed survey works.
        mockMvc.perform(post("/api/surveys/{id}/deploy", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/surveys/{id}/close", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("closed"));
    }

    @Test
    void closingADraftIsRejected() throws Exception {
        String token = coordinatorToken();
        String id = createSurvey(token, firstCommunityId(token), "Never Deployed").get("id").asText();

        mockMvc.perform(post("/api/surveys/{id}/close", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    // --- permissions (spec §2.2) ---

    @Test
    void studentVolunteerIsForbiddenFromSurveysAndCategories() throws Exception {
        String token = token(STUDENT_EMAIL, STUDENT_PASSWORD);

        mockMvc.perform(get("/api/surveys").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/need-categories").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void facultyCannotManageAnotherUsersSurvey() throws Exception {
        String coordinatorToken = coordinatorToken();
        String communityId = firstCommunityId(coordinatorToken);
        String foreignId = createSurvey(coordinatorToken, communityId, "Coordinator Owned").get("id").asText();

        String facultyToken = token(FACULTY_EMAIL, FACULTY_PASSWORD);

        // Managing someone else's survey is denied...
        mockMvc.perform(patch("/api/surveys/{id}", foreignId)
                        .header("Authorization", "Bearer " + facultyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"communityId":"%s","title":"Hijacked"}
                                """.formatted(communityId)))
                .andExpect(status().isForbidden());

        // ...and it is not even visible to them (no existence leak).
        mockMvc.perform(get("/api/surveys/{id}", foreignId)
                        .header("Authorization", "Bearer " + facultyToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void facultyCanCreateAndManageTheirOwnSurveyAndListShowsOnlyTheirs() throws Exception {
        String coordinatorToken = coordinatorToken();
        String communityId = firstCommunityId(coordinatorToken);
        createSurvey(coordinatorToken, communityId, "Coordinator Only Survey");

        String facultyToken = token(FACULTY_EMAIL, FACULTY_PASSWORD);
        JsonNode own = createSurvey(facultyToken, communityId, "Faculty Own Survey");

        mockMvc.perform(patch("/api/surveys/{id}", own.get("id").asText())
                        .header("Authorization", "Bearer " + facultyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"communityId":"%s","title":"Faculty Own Survey (edited)"}
                                """.formatted(communityId)))
                .andExpect(status().isOk());

        MvcResult list = mockMvc.perform(get("/api/surveys")
                        .header("Authorization", "Bearer " + facultyToken)
                        .param("per_page", "100"))
                .andExpect(status().isOk())
                .andReturn();
        String body = list.getResponse().getContentAsString();
        assertTrue(body.contains("Faculty Own Survey (edited)"), "faculty sees their own survey");
        assertFalse(body.contains("Coordinator Only Survey"), "faculty must not see other users' surveys");
    }

    @Test
    void adminSeesAllSurveys() throws Exception {
        String coordinatorToken = coordinatorToken();
        createSurvey(coordinatorToken, firstCommunityId(coordinatorToken), "Visible To Admin");

        MvcResult list = mockMvc.perform(get("/api/surveys")
                        .header("Authorization", "Bearer " + adminToken())
                        .param("per_page", "100"))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(list.getResponse().getContentAsString().contains("Visible To Admin"));
    }

    // --- helpers ---

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder addQuestion(
            String surveyId, String token, String payload) {
        return post("/api/surveys/{id}/questions", surveyId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload);
    }

    private JsonNode createSurvey(String token, String communityId, String title) throws Exception {
        String body = mockMvc.perform(post("/api/surveys")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"communityId":"%s","title":"%s","description":"Pass A test survey"}
                                """.formatted(communityId, title)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private String firstCommunityId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/communities").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get(0).get("id").asText();
    }

    private String firstNeedCategoryId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/need-categories").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get(0).get("id").asText();
    }

    private String adminToken() throws Exception {
        return token(ADMIN_EMAIL, ADMIN_PASSWORD);
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
