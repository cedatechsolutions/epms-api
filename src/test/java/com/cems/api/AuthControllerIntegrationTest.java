package com.cems.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
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
                .andExpect(jsonPath("$.message").value("Invalid email or password."));
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
                .andExpect(status().isForbidden());
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
        JsonNode users = objectMapper.readTree(responseBody);
        for (JsonNode userNode : users) {
            emails.add(userNode.get("email").asText());
        }
        return emails;
    }
}
