package com.cems.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void loginReturnsJwtAndAllowsAccessToProtectedEndpoint() throws Exception {
        String accessToken = loginAndGetAccessToken("superadmin@cems.com", "SuperAdmin123!");

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("superadmin@cems.com"))
                .andExpect(jsonPath("$.firstName").value("Super"))
                .andExpect(jsonPath("$.lastName").value("Admin"))
                .andExpect(jsonPath("$.contactNumber").value("09170000000"));
    }

    @Test
    void loginRejectsInvalidCredentials() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "superadmin@cems.com",
                                  "password": "wrong-password"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid email or password."))
                .andExpect(jsonPath("$.path").value("/api/auth/login"));
    }

    @Test
    void logoutRevokesTokenAndBlocksFurtherAccess() throws Exception {
        String accessToken = loginAndGetAccessToken("superadmin@cems.com", "SuperAdmin123!");

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully."));

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication is required."))
                .andExpect(jsonPath("$.path").value("/api/users/me"));
    }

    @Test
    void superAdminCanCreateUpdateAndDeleteManagedAccounts() throws Exception {
        String superAdminToken = loginAndGetAccessToken("superadmin@cems.com", "SuperAdmin123!");

        JsonNode createdAdmin = createUser(superAdminToken, """
                {
                  "email": "managed.admin@cems.com",
                  "password": "ManagedAdmin123!",
                  "firstName": "Managed",
                  "lastName": "Admin",
                  "middleName": "Alpha",
                  "contactNumber": "09170000011",
                  "role": "ADMIN"
                }
                """);

        JsonNode createdUser = createUser(superAdminToken, """
                {
                  "email": "managed.user@cems.com",
                  "password": "ManagedUser123!",
                  "firstName": "Managed",
                  "lastName": "User",
                  "middleName": "Beta",
                  "contactNumber": "09170000012",
                  "role": "USER"
                }
                """);

        String managedAdminId = createdAdmin.get("id").asText();
        String managedUserId = createdUser.get("id").asText();

        mockMvc.perform(put("/api/users/{userId}", managedUserId)
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "managed.user@cems.com",
                                  "password": "UpdatedUser123!",
                                  "firstName": "Managed",
                                  "lastName": "User",
                                  "middleName": "Gamma",
                                  "contactNumber": "09170000999",
                                  "role": "USER"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.middleName").value("Gamma"))
                .andExpect(jsonPath("$.contactNumber").value("09170000999"));

        mockMvc.perform(delete("/api/users/{userId}", managedAdminId)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("User deleted successfully."));

        MvcResult listResult = mockMvc.perform(get("/api/users")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andReturn();

        Set<String> emails = extractEmails(listResult.getResponse().getContentAsString());
        assertTrue(emails.contains("managed.user@cems.com"));
        assertFalse(emails.contains("managed.admin@cems.com"));
    }

    @Test
    void adminCanListUsersButCannotCreateAccounts() throws Exception {
        String superAdminToken = loginAndGetAccessToken("superadmin@cems.com", "SuperAdmin123!");

        createUser(superAdminToken, """
                {
                  "email": "readonly.admin@cems.com",
                  "password": "ReadOnlyAdmin123!",
                  "firstName": "Read",
                  "lastName": "Only",
                  "middleName": "Admin",
                  "contactNumber": "09170000021",
                  "role": "ADMIN"
                }
                """);

        String adminToken = loginAndGetAccessToken("readonly.admin@cems.com", "ReadOnlyAdmin123!");

        mockMvc.perform(get("/api/users")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "forbidden.admin.create@cems.com",
                                  "password": "Forbidden123!",
                                  "firstName": "Forbidden",
                                  "lastName": "Create",
                                  "middleName": "Admin",
                                  "contactNumber": "09170000022",
                                  "role": "USER"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Access is denied."))
                .andExpect(jsonPath("$.path").value("/api/users"));
    }

    @Test
    void validationErrorsUseStandardErrorShape() throws Exception {
        String superAdminToken = loginAndGetAccessToken("superadmin@cems.com", "SuperAdmin123!");

        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "not-an-email",
                                  "password": "",
                                  "firstName": "",
                                  "lastName": "User",
                                  "middleName": "Validation",
                                  "contactNumber": "09170000051",
                                  "role": "USER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Validation failed."))
                .andExpect(jsonPath("$.path").value("/api/users"))
                .andExpect(jsonPath("$.errors.email").isArray())
                .andExpect(jsonPath("$.errors.password").isArray())
                .andExpect(jsonPath("$.errors.firstName").isArray());
    }

    @Test
    void userListSupportsPaginationSearchFiltersAndSorting() throws Exception {
        String superAdminToken = loginAndGetAccessToken("superadmin@cems.com", "SuperAdmin123!");

        createUser(superAdminToken, """
                {
                  "email": "filter.admin@cems.com",
                  "password": "FilterAdmin123!",
                  "firstName": "Filter",
                  "lastName": "Admin",
                  "middleName": "Query",
                  "contactNumber": "09170000061",
                  "role": "ADMIN",
                  "status": "ACTIVE"
                }
                """);

        createUser(superAdminToken, """
                {
                  "email": "filter.user@cems.com",
                  "password": "FilterUser123!",
                  "firstName": "Filter",
                  "lastName": "User",
                  "middleName": "Query",
                  "contactNumber": "09170000062",
                  "role": "USER",
                  "status": "INACTIVE"
                }
                """);

        mockMvc.perform(get("/api/users")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("page", "1")
                        .param("perPage", "1")
                        .param("search", "filter")
                        .param("role", "USER")
                        .param("status", "INACTIVE")
                        .param("sort", "email")
                        .param("direction", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.current_page").value(1))
                .andExpect(jsonPath("$.meta.per_page").value(1))
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].email").value("filter.user@cems.com"));

        MvcResult adminFilterResult = mockMvc.perform(get("/api/users")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .param("search", "filter")
                        .param("role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1))
                .andReturn();

        Set<String> emails = extractEmails(adminFilterResult.getResponse().getContentAsString());
        assertTrue(emails.contains("filter.admin@cems.com"));
        assertFalse(emails.contains("filter.user@cems.com"));
    }

    @Test
    void adminCanPrintUsersPdf() throws Exception {
        String superAdminToken = loginAndGetAccessToken("superadmin@cems.com", "SuperAdmin123!");

        MvcResult printResult = mockMvc.perform(get("/api/users/print")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .accept("application/pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("application/pdf")))
                .andExpect(header().string("Content-Disposition", containsString("inline;")))
                .andExpect(header().string("Content-Disposition", containsString("cems-users.pdf")))
                .andReturn();

        byte[] pdfBytes = printResult.getResponse().getContentAsByteArray();
        String pdfText = new String(pdfBytes, StandardCharsets.ISO_8859_1);
        assertTrue(pdfText.startsWith("%PDF-"));
        assertTrue(pdfText.contains("CEMS User List"));
        assertTrue(pdfText.contains("superadmin@cems.com"));
    }

    @Test
    void superAdminCanDeactivateUserAndInactiveUserCannotAuthenticateOrUseExistingToken() throws Exception {
        String superAdminToken = loginAndGetAccessToken("superadmin@cems.com", "SuperAdmin123!");

        JsonNode createdUser = createUser(superAdminToken, """
                {
                  "email": "inactive.user@cems.com",
                  "password": "InactiveUser123!",
                  "firstName": "Inactive",
                  "lastName": "User",
                  "middleName": "State",
                  "contactNumber": "09170000031",
                  "role": "USER"
                }
                """);

        String managedUserId = createdUser.get("id").asText();
        String managedUserToken = loginAndGetAccessToken("inactive.user@cems.com", "InactiveUser123!");

        mockMvc.perform(patch("/api/users/{userId}/status", managedUserId)
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "INACTIVE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "inactive.user@cems.com",
                                  "password": "InactiveUser123!"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("This user account is inactive."));

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + managedUserToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication is required."));
    }

    @Test
    void superAdminCanResetManagedUserPasswordAndOnlyNewPasswordWorks() throws Exception {
        String superAdminToken = loginAndGetAccessToken("superadmin@cems.com", "SuperAdmin123!");

        JsonNode createdUser = createUser(superAdminToken, """
                {
                  "email": "reset.user@cems.com",
                  "password": "OriginalUser123!",
                  "firstName": "Reset",
                  "lastName": "User",
                  "middleName": "Flow",
                  "contactNumber": "09170000041",
                  "role": "USER"
                }
                """);

        String managedUserId = createdUser.get("id").asText();

        mockMvc.perform(patch("/api/users/{userId}/password", managedUserId)
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "password": "UpdatedUser123!",
                                  "passwordConfirmation": "UpdatedUser123!"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password reset successfully."));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "reset.user@cems.com",
                                  "password": "OriginalUser123!"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password."));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "reset.user@cems.com",
                                  "password": "UpdatedUser123!"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("reset.user@cems.com"));
    }

    private String loginAndGetAccessToken(String email, String password) throws Exception {
        String loginResponse = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s"
                                }
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.email").value(email))
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(loginResponse).get("accessToken").asText();
    }

    private JsonNode createUser(String accessToken, String payload) throws Exception {
        String responseBody = mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(responseBody);
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
