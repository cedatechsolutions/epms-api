package com.cems.api;

import com.cems.api.service.EmailService;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Coverage of the self-service Profile Settings surface: a signed-in user editing their own
 * personal information and profile photo through {@code /api/users/me}. Every test operates on a
 * purpose-created account so the seeded dev users other suites rely on are never mutated
 * (the in-memory database is shared across the suite).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserProfileControllerIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@cems.com";
    private static final String ADMIN_PASSWORD = "Admin123!";
    private static final String MEMBER_PASSWORD = "Member123!";

    /** 1x1 PNG — the smallest thing that is genuinely a PNG rather than bytes claiming to be one. */
    private static final byte[] PNG_BYTES = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @Autowired
    private MockMvc mockMvc;

    // No real mail: creating the fixture users goes through the user-management flow.
    @MockitoBean
    private EmailService emailService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- personal information ---

    @Test
    void userCanUpdateOwnPersonalInformation() throws Exception {
        String email = createMember("profile.edit");
        String token = token(email, MEMBER_PASSWORD);

        mockMvc.perform(put("/api/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Maria Clara","lastName":"Dela Cruz",
                                 "middleName":"Reyes","contactNumber":"09171234567"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Maria Clara"))
                .andExpect(jsonPath("$.lastName").value("Dela Cruz"))
                .andExpect(jsonPath("$.middleName").value("Reyes"))
                .andExpect(jsonPath("$.contactNumber").value("09171234567"));

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Maria Clara"))
                .andExpect(jsonPath("$.email").value(email));
    }

    /** Email, role and status are user-management's call — a self-service edit must not move them. */
    @Test
    void selfServiceEditCannotChangeEmailOrRole() throws Exception {
        String email = createMember("profile.identity");
        String token = token(email, MEMBER_PASSWORD);

        mockMvc.perform(put("/api/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Jose","lastName":"Rizal","middleName":"P",
                                 "contactNumber":"09170000000","email":"hijacked@cems.com",
                                 "role":"admin","status":"INACTIVE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.roles[0]").value("faculty"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void blankNameIsRejectedWith422() throws Exception {
        String token = token(createMember("profile.blank"), MEMBER_PASSWORD);

        mockMvc.perform(put("/api/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"  ","lastName":"Santos","middleName":"","contactNumber":""}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.firstName").isArray());
    }

    // --- profile photo ---

    @Test
    void userCanUploadDownloadAndRemoveOwnAvatar() throws Exception {
        String token = token(createMember("profile.avatar"), MEMBER_PASSWORD);

        // No photo yet: the flag is absent and the bytes endpoint 404s.
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUpdatedAt").doesNotExist());
        mockMvc.perform(get("/api/users/me/avatar")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        mockMvc.perform(multipart("/api/users/me/avatar")
                        .file(new MockMultipartFile("file", "me.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUpdatedAt").exists());

        byte[] served = mockMvc.perform(get("/api/users/me/avatar")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(PNG_BYTES, served, "the stored bytes should be served back unchanged");

        // Replacing keeps exactly one photo; removing clears the flag and the bytes.
        mockMvc.perform(multipart("/api/users/me/avatar")
                        .file(new MockMultipartFile("file", "me2.jpg", "image/jpeg", PNG_BYTES))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUpdatedAt").exists());

        mockMvc.perform(delete("/api/users/me/avatar")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUpdatedAt").doesNotExist());

        mockMvc.perform(get("/api/users/me/avatar")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonImageAvatarIsRejectedWith422() throws Exception {
        String token = token(createMember("profile.notimage"), MEMBER_PASSWORD);

        // A PDF passes the generic upload whitelist, so the avatar rule has to reject it itself.
        mockMvc.perform(multipart("/api/users/me/avatar")
                        .file(new MockMultipartFile("file", "resume.pdf", "application/pdf", "%PDF-1.4".getBytes()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void oversizeAvatarIsRejectedWith422() throws Exception {
        String token = token(createMember("profile.oversize"), MEMBER_PASSWORD);

        // 2 MB + 1 byte — over the avatar cap but under the generic 10 MB upload limit.
        byte[] tooBig = new byte[(2 * 1024 * 1024) + 1];
        mockMvc.perform(multipart("/api/users/me/avatar")
                        .file(new MockMultipartFile("file", "huge.png", "image/png", tooBig))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity());
    }

    // --- administrator managing another account's photo and optional fields ---

    @Test
    void adminCanCreateUserWithoutMiddleNameOrContactNumber() throws Exception {
        // Both fields are optional; omitting them stores null rather than an empty string.
        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + token(ADMIN_EMAIL, ADMIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"minimal.fields@cems.com","password":"%s","firstName":"Andres",
                                 "lastName":"Bonifacio","role":"faculty","status":"ACTIVE"}
                                """.formatted(MEMBER_PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.middleName").doesNotExist())
                .andExpect(jsonPath("$.contactNumber").doesNotExist());

        // Blank strings are treated the same as omitted, not stored as "".
        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + token(ADMIN_EMAIL, ADMIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"blank.fields@cems.com","password":"%s","firstName":"Gregoria",
                                 "lastName":"De Jesus","middleName":"  ","contactNumber":"",
                                 "role":"faculty","status":"ACTIVE"}
                                """.formatted(MEMBER_PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.middleName").doesNotExist())
                .andExpect(jsonPath("$.contactNumber").doesNotExist());
    }

    @Test
    void adminCanSetAndClearAnotherUsersAvatar() throws Exception {
        String adminToken = token(ADMIN_EMAIL, ADMIN_PASSWORD);
        String memberEmail = createMember("profile.managed");
        String userId = userIdOf(adminToken, memberEmail);

        mockMvc.perform(multipart("/api/users/{userId}/avatar", userId)
                        .file(new MockMultipartFile("file", "hire.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUpdatedAt").exists());

        mockMvc.perform(get("/api/users/{userId}/avatar", userId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"));

        // The owner sees the photo the administrator set on their behalf.
        mockMvc.perform(get("/api/users/me", userId)
                        .header("Authorization", "Bearer " + token(memberEmail, MEMBER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUpdatedAt").exists());

        mockMvc.perform(delete("/api/users/{userId}/avatar", userId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUpdatedAt").doesNotExist());
    }

    @Test
    void nonAdminCannotManageAnotherUsersAvatar() throws Exception {
        String adminToken = token(ADMIN_EMAIL, ADMIN_PASSWORD);
        String targetId = userIdOf(adminToken, createMember("profile.target"));
        String outsiderToken = token(createMember("profile.outsider"), MEMBER_PASSWORD);

        mockMvc.perform(multipart("/api/users/{userId}/avatar", targetId)
                        .file(new MockMultipartFile("file", "nope.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/users/{userId}/avatar", targetId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/users/{userId}/avatar", targetId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void profileEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(put("/api/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"A\",\"lastName\":\"B\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/users/me/avatar"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/users/me/avatar"))
                .andExpect(status().isUnauthorized());
    }

    // --- helpers ---

    /** Creates an active faculty account with a known password (no forced password change). */
    private String createMember(String localPart) throws Exception {
        String email = localPart + "@cems.com";
        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + token(ADMIN_EMAIL, ADMIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","firstName":"Fixture","lastName":"Member",
                                 "middleName":"T","contactNumber":"09170000001","role":"faculty",
                                 "status":"ACTIVE"}
                                """.formatted(email, MEMBER_PASSWORD)))
                .andExpect(status().isCreated());
        return email;
    }

    /** Resolves a user's id through the admin list endpoint (search matches on email). */
    private String userIdOf(String adminToken, String email) throws Exception {
        String body = mockMvc.perform(get("/api/users")
                        .param("search", email)
                        .header("Authorization", "Bearer " + adminToken))
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
