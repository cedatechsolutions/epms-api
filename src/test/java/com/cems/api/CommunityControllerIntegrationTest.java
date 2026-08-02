package com.cems.api;

import com.cems.api.service.EmailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of Module 2 (spec §Module 2): community CRUD, sector tagging, the
 * permission matrix (view for all roles, manage for admin + coordinators), soft delete,
 * the GAD population-split warning, and document upload/download/delete with validation.
 * Seeded dev accounts (see {@code DataInitializer}) are used read-only for auth.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CommunityControllerIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@cems.com";
    private static final String ADMIN_PASSWORD = "Admin123!";
    private static final String COORDINATOR_EMAIL = "coordinator@cems.com";
    private static final String COORDINATOR_PASSWORD = "Coordinator123!";
    private static final String FACULTY_EMAIL = "faculty@cems.com";
    private static final String FACULTY_PASSWORD = "Faculty123!";
    private static final String STUDENT_EMAIL = "student@cems.com";
    private static final String STUDENT_PASSWORD = "Student123!";
    private static final String CAMPUS_ADMIN_EMAIL = "campus.admin@cems.com";
    private static final String CAMPUS_ADMIN_PASSWORD = "CampusAdmin123!";

    @Autowired
    private MockMvc mockMvc;

    // No real mail during tests (temp-password email fires on some flows through shared seeding).
    @MockitoBean
    private EmailService emailService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- CRUD ---

    @Test
    void adminCanCreateGetUpdateAndListCommunity() throws Exception {
        String token = adminToken();
        String sectorId = firstSectorId(token);

        JsonNode created = createCommunity(token, """
                {"name":"Barangay CRUD","municipality":"Imus","province":"Cavite",
                 "classification":"rural","estimatedPopulation":1000,"householdCount":250,
                 "populationMale":500,"populationFemale":500,"sectorIds":["%s"]}
                """.formatted(sectorId));
        String id = created.get("id").asText();
        assertTrue(created.get("warnings").isArray());
        assertTrue(created.get("warnings").isEmpty(), "balanced population should not warn");

        mockMvc.perform(get("/api/communities/{id}", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Barangay CRUD"))
                .andExpect(jsonPath("$.classification").value("rural"))
                .andExpect(jsonPath("$.sectors[0].id").value(sectorId));

        mockMvc.perform(patch("/api/communities/{id}", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Barangay CRUD (edited)","municipality":"Imus","province":"Cavite",
                                 "classification":"urban","sectorIds":[]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Barangay CRUD (edited)"))
                .andExpect(jsonPath("$.classification").value("urban"))
                .andExpect(jsonPath("$.sectors").isEmpty());

        MvcResult list = mockMvc.perform(get("/api/communities")
                        .header("Authorization", "Bearer " + token)
                        .param("search", "edited"))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(list.getResponse().getContentAsString().contains("Barangay CRUD (edited)"));
    }

    @Test
    void populationSplitBeyondTenPercentAddsNonBlockingWarning() throws Exception {
        String token = adminToken();
        JsonNode created = createCommunity(token, """
                {"name":"Skewed Split","municipality":"Dasmarinas","province":"Cavite",
                 "classification":"urban_poor","estimatedPopulation":1000,
                 "populationMale":100,"populationFemale":100}
                """);
        assertFalse(created.get("warnings").isEmpty(), "200 vs 1000 estimate should warn");
    }

    @Test
    void invalidClassificationReturns422() throws Exception {
        mockMvc.perform(post("/api/communities")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Bad Class","municipality":"Bacoor","province":"Cavite",
                                 "classification":"metropolis"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void missingRequiredFieldsReturn422WithFieldDetails() throws Exception {
        mockMvc.perform(post("/api/communities")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","municipality":"","province":"","classification":""}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.name").isArray());
    }

    // --- soft delete ---

    @Test
    void deleteIsSoftAndHidesCommunityFromListAndDetail() throws Exception {
        String token = adminToken();
        String id = createCommunity(token, minimalBody("To Be Deleted", "Kawit")).get("id").asText();

        mockMvc.perform(delete("/api/communities/{id}", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/communities/{id}", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        MvcResult list = mockMvc.perform(get("/api/communities")
                        .header("Authorization", "Bearer " + token)
                        .param("search", "To Be Deleted"))
                .andExpect(status().isOk())
                .andReturn();
        assertFalse(list.getResponse().getContentAsString().contains("To Be Deleted"));
    }

    // --- permissions (spec §2.2) ---

    @Test
    void allRolesCanViewCommunities() throws Exception {
        for (String[] account : new String[][]{
                {ADMIN_EMAIL, ADMIN_PASSWORD},
                {CAMPUS_ADMIN_EMAIL, CAMPUS_ADMIN_PASSWORD},
                {COORDINATOR_EMAIL, COORDINATOR_PASSWORD},
                {FACULTY_EMAIL, FACULTY_PASSWORD},
                {STUDENT_EMAIL, STUDENT_PASSWORD}}) {
            String token = token(account[0], account[1]);
            mockMvc.perform(get("/api/communities").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/sectors").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void coordinatorRolesCanManageCommunities() throws Exception {
        // extension_coordinator is a manage role → create must succeed.
        mockMvc.perform(post("/api/communities")
                        .header("Authorization", "Bearer " + token(COORDINATOR_EMAIL, COORDINATOR_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(minimalBody("Coordinator Made", "Noveleta")))
                .andExpect(status().isCreated());
    }

    @Test
    void viewOnlyRolesAreForbiddenFromManaging() throws Exception {
        for (String[] account : new String[][]{
                {FACULTY_EMAIL, FACULTY_PASSWORD},
                {STUDENT_EMAIL, STUDENT_PASSWORD},
                {CAMPUS_ADMIN_EMAIL, CAMPUS_ADMIN_PASSWORD}}) {
            String token = token(account[0], account[1]);
            mockMvc.perform(post("/api/communities")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(minimalBody("Should Fail", "Tanza")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        }
    }

    // --- documents ---

    @Test
    void documentUploadListDownloadAndDeleteLifecycle() throws Exception {
        String token = adminToken();
        String id = createCommunity(token, minimalBody("Docs Home", "Silang")).get("id").asText();

        MockMultipartFile pdf = new MockMultipartFile(
                "file", "moa.pdf", "application/pdf", "%PDF-1.4 sample".getBytes());

        MvcResult upload = mockMvc.perform(multipart("/api/communities/{id}/documents", id)
                        .file(pdf)
                        .param("docType", "moa")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalFilename").value("moa.pdf"))
                .andExpect(jsonPath("$.docType").value("moa"))
                .andReturn();
        String docId = objectMapper.readTree(upload.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/communities/{id}/documents", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(docId));

        MvcResult download = mockMvc.perform(get("/api/communities/{id}/documents/{docId}", id, docId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(download.getResponse().getContentAsString().contains("%PDF-1.4"));

        mockMvc.perform(delete("/api/communities/{id}/documents/{docId}", id, docId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/communities/{id}/documents", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void disallowedFileTypeIsRejectedWith422() throws Exception {
        String token = adminToken();
        String id = createCommunity(token, minimalBody("Reject Type", "Rosario")).get("id").asText();

        MockMultipartFile exe = new MockMultipartFile(
                "file", "malware.exe", "application/octet-stream", "MZ".getBytes());

        mockMvc.perform(multipart("/api/communities/{id}/documents", id)
                        .file(exe)
                        .param("docType", "other")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void oversizeFileIsRejectedWith422() throws Exception {
        String token = adminToken();
        String id = createCommunity(token, minimalBody("Reject Size", "Gen. Trias")).get("id").asText();

        // 10 MB + 1 byte, just over the limit.
        byte[] tooBig = new byte[(10 * 1024 * 1024) + 1];
        MockMultipartFile big = new MockMultipartFile(
                "file", "huge.pdf", "application/pdf", tooBig);

        mockMvc.perform(multipart("/api/communities/{id}/documents", id)
                        .file(big)
                        .param("docType", "other")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity());
    }

    // --- history (Phase 1 stub) ---

    @Test
    void historyReturnsEmptyListUntilProgramsExist() throws Exception {
        String token = adminToken();
        String id = createCommunity(token, minimalBody("History Home", "Trece Martires")).get("id").asText();

        mockMvc.perform(get("/api/communities/{id}/history", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    // --- helpers ---

    private String adminToken() throws Exception {
        return token(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    private String token(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private String firstSectorId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/sectors")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode sectors = objectMapper.readTree(body);
        assertNotNull(sectors.get(0), "sectors should be seeded by migration V4");
        return sectors.get(0).get("id").asText();
    }

    private JsonNode createCommunity(String token, String payload) throws Exception {
        String body = mockMvc.perform(post("/api/communities")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private String minimalBody(String name, String municipality) {
        return """
                {"name":"%s","municipality":"%s","province":"Cavite","classification":"rural"}
                """.formatted(name, municipality);
    }
}
