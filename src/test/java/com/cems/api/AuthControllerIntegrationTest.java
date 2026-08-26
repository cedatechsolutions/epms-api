package com.cems.api;

import com.cems.api.service.EmailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of Module 1 (spec §4): the six-role model, rotating refresh tokens,
 * account lockout, forgot/reset/change password, soft delete, the standard error envelope,
 * and permission enforcement. Seeded dev accounts (see {@code DataInitializer}) are used
 * read-only; any test that mutates credentials/state operates on a purpose-created user so
 * tests stay independent on the shared in-memory database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@cems.com";
    private static final String ADMIN_PASSWORD = "Admin123!";
    private static final String FACULTY_EMAIL = "faculty@cems.com";
    private static final String FACULTY_PASSWORD = "Faculty123!";

    @Autowired
    private MockMvc mockMvc;

    // Replaced with a mock so no real mail is sent and reset links can be captured.
    @MockitoBean
    private EmailService emailService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Authentication ---

    @Test
    void loginReturnsTokenPairAndAllowsProtectedAccess() throws Exception {
        JsonNode login = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        assertTrue(login.get("accessToken").asText().length() > 0);
        assertTrue(login.get("refreshToken").asText().length() > 0);
        assertTrue(login.has("mustChangePassword"));

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + login.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(ADMIN_EMAIL))
                .andExpect(jsonPath("$.roles[0]").value("admin"));
    }

    @Test
    void invalidCredentialsReturn401WithStandardEnvelope() throws Exception {
        // Unknown email → no side effect on any real account.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("ghost@cems.com", "whatever")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.message").value("Invalid email or password."));
    }

    @Test
    void accountLocksAfterFiveFailedAttemptsAndReturns423() throws Exception {
        String token = adminToken();
        String email = "lockout.target@cems.com";
        createUser(token, userBody(email, "LockTarget123!", "faculty", "active"));

        for (int attempt = 1; attempt <= 4; attempt++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody(email, "wrong-password")))
                    .andExpect(status().isUnauthorized());
        }

        // Fifth failure trips the lock.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, "wrong-password")))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error.code").value("LOCKED"));

        // Even the correct password is refused while locked.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, "LockTarget123!")))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error.code").value("LOCKED"));
    }

    // --- Refresh rotation ---

    @Test
    void refreshRotatesTokensAndRejectsReuse() throws Exception {
        String refreshA = login(ADMIN_EMAIL, ADMIN_PASSWORD).get("refreshToken").asText();

        JsonNode rotated = refresh(refreshA);
        assertTrue(rotated.get("accessToken").asText().length() > 0);
        String refreshB = rotated.get("refreshToken").asText();
        assertFalse(refreshA.equals(refreshB));

        // Replaying the rotated-away token is rejected and revokes the family.
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refreshA)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refreshB)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesRefreshAndBlocksAccessToken() throws Exception {
        JsonNode login = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        String accessToken = login.get("accessToken").asText();
        String refreshToken = login.get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    // --- Forgot / reset / change password ---

    @Test
    void forgotAndResetPasswordFlow() throws Exception {
        String token = adminToken();
        String email = "reset.flow@cems.com";
        createUser(token, userBody(email, "OriginalPass123!", "faculty", "active"));

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService, timeout(2000)).sendPasswordResetEmail(eq(email), linkCaptor.capture());
        String resetToken = linkCaptor.getValue().substring(linkCaptor.getValue().indexOf("token=") + 6);

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","password":"BrandNew123!","passwordConfirmation":"BrandNew123!"}
                                """.formatted(resetToken)))
                .andExpect(status().isOk());

        // New password works; old fails; token cannot be reused.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, "BrandNew123!")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, "OriginalPass123!")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","password":"Another123!","passwordConfirmation":"Another123!"}
                                """.formatted(resetToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changePasswordRequiresAuthenticationAndUpdatesCredential() throws Exception {
        String token = adminToken();
        String email = "change.pw@cems.com";
        createUser(token, userBody(email, "StartPass123!", "faculty", "active"));
        String access = login(email, "StartPass123!").get("accessToken").asText();

        mockMvc.perform(post("/api/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"StartPass123!","newPassword":"Changed123!","newPasswordConfirmation":"Changed123!"}
                                """))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"StartPass123!","newPassword":"Changed123!","newPasswordConfirmation":"Changed123!"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, "Changed123!")))
                .andExpect(status().isOk());
    }

    // --- User management + permissions ---

    @Test
    void adminCanCreateUpdateAndSoftDeleteUsers() throws Exception {
        String token = adminToken();
        JsonNode created = createUser(token, userBody("crud.user@cems.com", "CrudUser123!", "faculty", "active"));
        String userId = created.get("id").asText();

        mockMvc.perform(put("/api/users/{id}", userId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"crud.user@cems.com","firstName":"Crud","lastName":"User",
                                 "middleName":"Edited","contactNumber":"09170009999","role":"extension_coordinator"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.middleName").value("Edited"))
                .andExpect(jsonPath("$.roles[0]").value("extension_coordinator"));

        mockMvc.perform(delete("/api/users/{id}", userId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Soft-deleted users are excluded from the list.
        MvcResult listResult = mockMvc.perform(get("/api/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertFalse(extractEmails(listResult.getResponse().getContentAsString()).contains("crud.user@cems.com"));
    }

    @Test
    void validationErrorsReturn422WithFieldDetails() throws Exception {
        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","firstName":"","lastName":"User",
                                 "middleName":"V","contactNumber":"1","role":"faculty"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.email").isArray())
                .andExpect(jsonPath("$.error.fields.firstName").isArray());
    }

    @Test
    void creatingUserWithoutPasswordEmailsTemporaryPasswordAndForcesChange() throws Exception {
        String email = "temp.pw@cems.com";
        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","firstName":"Temp","lastName":"User",
                                 "middleName":"Pw","contactNumber":"09170002222","role":"faculty","status":"active"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mustChangePassword").value(true));

        verify(emailService, timeout(2000)).sendTemporaryPasswordEmail(eq(email), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void duplicateEmailReturns409Conflict() throws Exception {
        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userBody(ADMIN_EMAIL, "Whatever123!", "faculty", "active")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @Test
    void nonAdminRolesAreForbiddenFromUserManagementAndAuditLog() throws Exception {
        String facultyToken = login(FACULTY_EMAIL, FACULTY_PASSWORD).get("accessToken").asText();

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + facultyToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/activity-logs").header("Authorization", "Bearer " + facultyToken))
                .andExpect(status().isForbidden());
    }

    /**
     * The people-picker directory is the one /api/users route a non-admin may read (spec Module 5 §2
     * — a coordinator has to be able to name the faculty lead). These three assertions pin the whole
     * contract: faculty get in, student volunteers do not, and the payload stays narrow.
     */
    @Test
    void userDirectoryIsReadableByProposalAuthorsButExposesOnlyNameAndRoles() throws Exception {
        String facultyToken = login(FACULTY_EMAIL, FACULTY_PASSWORD).get("accessToken").asText();

        MvcResult result = mockMvc.perform(get("/api/users/directory")
                        .header("Authorization", "Bearer " + facultyToken)
                        .param("role", "faculty"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode options = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(options.size() > 0, "the seeded faculty account should be listed");
        for (JsonNode option : options) {
            assertTrue(option.has("id") && option.has("name") && option.has("roles"));
            assertFalse(option.has("active"), "account state must stay behind canManageUsers()");
            assertFalse(option.has("lastLoginAt"), "account state must stay behind canManageUsers()");
            assertFalse(option.has("mustChangePassword"), "account state must stay behind canManageUsers()");
        }

        // Student volunteers cannot author proposals, so they cannot browse people either.
        String studentToken = login("student@cems.com", "Student123!").get("accessToken").asText();
        mockMvc.perform(get("/api/users/directory").header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isForbidden());
    }

    /** An unknown role filter yields no options rather than a 500 — a stale client must degrade. */
    @Test
    void userDirectoryReturnsEmptyForAnUnknownRole() throws Exception {
        mockMvc.perform(get("/api/users/directory")
                        .header("Authorization", "Bearer " + adminToken())
                        .param("role", "not_a_role"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void deactivatedUserCannotLogIn() throws Exception {
        String token = adminToken();
        JsonNode created = createUser(token, userBody("deactivate.me@cems.com", "Active123!", "faculty", "active"));

        mockMvc.perform(patch("/api/users/{id}/status", created.get("id").asText())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("deactivate.me@cems.com", "Active123!")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.message").value("This user account is inactive."));
    }

    // --- Activity log ---

    @Test
    void activityLogCapturesLoginAndUserCreation() throws Exception {
        String token = adminToken();
        createUser(token, userBody("audit.subject@cems.com", "Audit123!", "faculty", "active"));

        // The write is async/after-commit; poll briefly for it to land.
        boolean found = false;
        for (int attempt = 0; attempt < 20 && !found; attempt++) {
            MvcResult result = mockMvc.perform(get("/api/activity-logs")
                            .header("Authorization", "Bearer " + token)
                            .param("action", "user.created")
                            .param("per_page", "50"))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
            for (JsonNode row : data) {
                if (row.get("metadata").asText().contains("audit.subject@cems.com")) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                Thread.sleep(100);
            }
        }
        assertTrue(found, "Expected a user.created activity log for the created user");
    }

    // --- helpers ---

    private String adminToken() throws Exception {
        return login(ADMIN_EMAIL, ADMIN_PASSWORD).get("accessToken").asText();
    }

    private JsonNode login(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode refresh(String refreshToken) throws Exception {
        String body = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode createUser(String token, String payload) throws Exception {
        String body = mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private String loginBody(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
    }

    private String refreshBody(String refreshToken) {
        return "{\"refreshToken\":\"%s\"}".formatted(refreshToken);
    }

    private String userBody(String email, String password, String role, String status) {
        return """
                {"email":"%s","password":"%s","firstName":"Test","lastName":"User",
                 "middleName":"M","contactNumber":"09170001234","role":"%s","status":"%s"}
                """.formatted(email, password, role, status);
    }

    private Set<String> extractEmails(String responseBody) throws Exception {
        Set<String> emails = new HashSet<>();
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode users = root.isArray() ? root : root.get("data");
        for (JsonNode userNode : users) {
            emails.add(userNode.get("email").asText());
        }
        return emails;
    }
}
