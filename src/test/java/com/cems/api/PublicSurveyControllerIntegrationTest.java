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

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pass B coverage of Module 3 (spec §Module 3 §2/§3): the unauthenticated public survey form and
 * submission. The server is authoritative — closing dates, required questions, allowed answer
 * values, the mandatory GAD sex field, and RA-10173 consent are enforced regardless of the client.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicSurveyControllerIntegrationTest {

    private static final String COORDINATOR_EMAIL = "coordinator@cems.com";
    private static final String COORDINATOR_PASSWORD = "Coordinator123!";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmailService emailService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- reading the public form ---

    @Test
    void deployedSurveyIsPubliclyReadableWithoutAuthAndExposesNoScoringMetadata() throws Exception {
        Deployed deployed = deploySurvey(null);

        MvcResult result = mockMvc.perform(get("/api/public/surveys/{token}", deployed.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Public Survey"))
                .andExpect(jsonPath("$.questions[0].questionText").value("Rate health access"))
                .andExpect(jsonPath("$.sectors").isArray())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // The public surface must not leak scoring internals.
        assertFalse(body.contains("weight"), "public payload must not expose question weights");
        assertFalse(body.contains("needCategory"), "public payload must not expose need categories");
    }

    @Test
    void unknownTokenReturns404() throws Exception {
        mockMvc.perform(get("/api/public/surveys/{token}", "not-a-real-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void closedSurveyReturns410OnReadAndSubmit() throws Exception {
        Deployed deployed = deploySurvey(null);
        mockMvc.perform(post("/api/surveys/{id}/close", deployed.surveyId())
                        .header("Authorization", "Bearer " + coordinatorToken()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/public/surveys/{token}", deployed.token()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("GONE"));

        mockMvc.perform(submit(deployed, """
                        {"respondentSex":"female","consent":true,
                         "answers":[{"questionId":"%s","value":"4"}]}
                        """.formatted(deployed.ratingQuestionId())))
                .andExpect(status().isGone());
    }

    @Test
    void submissionAfterClosingDateIsRejectedServerSide() throws Exception {
        // Deployed, but its closing date is already in the past.
        Deployed deployed = deploySurvey(Instant.now().minus(1, ChronoUnit.HOURS));

        mockMvc.perform(get("/api/public/surveys/{token}", deployed.token()))
                .andExpect(status().isGone());

        mockMvc.perform(submit(deployed, """
                        {"respondentSex":"male","consent":true,
                         "answers":[{"questionId":"%s","value":"5"}]}
                        """.formatted(deployed.ratingQuestionId())))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("GONE"));
    }

    // --- submitting ---

    @Test
    void validSubmissionIsRecordedWithSexDisaggregation() throws Exception {
        Deployed deployed = deploySurvey(null);

        mockMvc.perform(submit(deployed, """
                        {"respondentSex":"female","respondentAgeGroup":"18_30","respondentToken":"device-1",
                         "consent":true,
                         "answers":[{"questionId":"%s","value":"4"},
                                    {"questionId":"%s","values":["clinic","pharmacy"]}]}
                        """.formatted(deployed.ratingQuestionId(), deployed.checkboxQuestionId())))
                .andExpect(status().isCreated());

        // The coordinator sees the response reflected on the survey list.
        MvcResult list = mockMvc.perform(get("/api/surveys")
                        .header("Authorization", "Bearer " + coordinatorToken())
                        .param("per_page", "100"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = objectMapper.readTree(list.getResponse().getContentAsString()).get("data");
        long count = 0;
        for (JsonNode row : rows) {
            if (row.get("id").asText().equals(deployed.surveyId())) {
                count = row.get("responseCount").asLong();
            }
        }
        assertEquals(1, count, "the survey list should report the recorded response");
    }

    @Test
    void missingSexIsRejectedBecauseGadIsMandatory() throws Exception {
        Deployed deployed = deploySurvey(null);

        mockMvc.perform(submit(deployed, """
                        {"consent":true,"answers":[{"questionId":"%s","value":"4"}]}
                        """.formatted(deployed.ratingQuestionId())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void missingConsentIsRejected() throws Exception {
        Deployed deployed = deploySurvey(null);

        mockMvc.perform(submit(deployed, """
                        {"respondentSex":"male","consent":false,
                         "answers":[{"questionId":"%s","value":"4"}]}
                        """.formatted(deployed.ratingQuestionId())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void missingRequiredAnswerIsRejected() throws Exception {
        Deployed deployed = deploySurvey(null);

        // The rating question is required; omitting it must fail.
        mockMvc.perform(submit(deployed, """
                        {"respondentSex":"male","consent":true,"answers":[]}
                        """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void outOfRangeRatingIsRejected() throws Exception {
        Deployed deployed = deploySurvey(null);

        mockMvc.perform(submit(deployed, """
                        {"respondentSex":"male","consent":true,
                         "answers":[{"questionId":"%s","value":"9"}]}
                        """.formatted(deployed.ratingQuestionId())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void invalidCheckboxOptionIsRejected() throws Exception {
        Deployed deployed = deploySurvey(null);

        mockMvc.perform(submit(deployed, """
                        {"respondentSex":"male","consent":true,
                         "answers":[{"questionId":"%s","value":"3"},
                                    {"questionId":"%s","values":["not_an_option"]}]}
                        """.formatted(deployed.ratingQuestionId(), deployed.checkboxQuestionId())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void sameDeviceTokenCannotSubmitTwice() throws Exception {
        Deployed deployed = deploySurvey(null);
        String payload = """
                {"respondentSex":"female","respondentToken":"repeat-device","consent":true,
                 "answers":[{"questionId":"%s","value":"3"}]}
                """.formatted(deployed.ratingQuestionId());

        mockMvc.perform(submit(deployed, payload)).andExpect(status().isCreated());
        mockMvc.perform(submit(deployed, payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    // --- helpers ---

    private record Deployed(String surveyId, String token, String ratingQuestionId, String checkboxQuestionId) {
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder submit(
            Deployed deployed, String payload) {
        return post("/api/public/surveys/{token}/responses", deployed.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload);
    }

    /** Builds + deploys a survey with one required rating question and one checkbox question. */
    private Deployed deploySurvey(Instant closesAt) throws Exception {
        String token = coordinatorToken();
        String communityId = firstCommunityId(token);

        String closesClause = closesAt == null ? "" : ",\"closesAt\":\"%s\"".formatted(closesAt.toString());
        String surveyId = objectMapper.readTree(mockMvc.perform(post("/api/surveys")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"communityId":"%s","title":"Public Survey"%s}
                                """.formatted(communityId, closesClause)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        String ratingId = objectMapper.readTree(mockMvc.perform(post("/api/surveys/{id}/questions", surveyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"questionText":"Rate health access","questionType":"rating","weight":3.0,"required":true}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        String checkboxId = objectMapper.readTree(mockMvc.perform(post("/api/surveys/{id}/questions", surveyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"questionText":"Services used","questionType":"checkbox","weight":1.0,"required":false,
                                 "options":[{"label":"Clinic","value":"clinic"},{"label":"Pharmacy","value":"pharmacy"}]}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        String accessToken = objectMapper.readTree(mockMvc.perform(post("/api/surveys/{id}/deploy", surveyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("accessToken").asText();

        return new Deployed(surveyId, accessToken, ratingId, checkboxId);
    }

    private String firstCommunityId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/communities").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get(0).get("id").asText();
    }

    private String coordinatorToken() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}"
                                .formatted(COORDINATOR_EMAIL, COORDINATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }
}
