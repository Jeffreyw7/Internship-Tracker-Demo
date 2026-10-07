// Exercises real Spring Security, controllers, services, and persistence against an isolated in-memory test database.
package com.campus.tracker;

import com.campus.tracker.auth.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    private static final String PASSWORD = "Campus-test-123!";
    record Account(String email, MockHttpSession session) {}

    @Test void preferencesAreValidatedIsolatedAndPersistAcrossSessions() throws Exception {
        Account a = account(false), b = account(false);
        mvc.perform(get("/api/me/preferences").session(a.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.themePreference").value("system"));
        for (String preference : List.of("light", "system", "dark")) {
            mvc.perform(put("/api/me/preferences").session(a.session()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("themePreference", preference))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.themePreference").value(preference));
        }
        mvc.perform(get("/api/me/preferences").session(b.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.themePreference").value("system"));
        mvc.perform(put("/api/me/preferences").session(a.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("themePreference", "light", "userId", users.findByEmail(b.email()).orElseThrow().getId()))))
                .andExpect(status().isBadRequest());
        for (String invalid : List.of("{}", "{\"themePreference\":null}", "{\"themePreference\":\"DARK\"}", "{\"themePreference\":\"sepia\"}")) {
            mvc.perform(put("/api/me/preferences").session(a.session()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        }
        MvcResult login = mvc.perform(post("/api/auth/login").with(csrf()).param("email", a.email()).param("password", PASSWORD))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(get("/api/me/preferences").session((MockHttpSession) login.getRequest().getSession(false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.themePreference").value("dark"));
    }

    @Test void preferenceEndpointsRequireAuthenticationAndCsrf() throws Exception {
        mvc.perform(get("/api/me/preferences")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/me/preferences").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"themePreference\":\"dark\"}")).andExpect(status().isUnauthorized());
        Account a = account(false);
        mvc.perform(put("/api/me/preferences").session(a.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"themePreference\":\"dark\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/me/preferences").session(a.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.themePreference").value("system"));
    }

    // Create a unique synthetic account and capture its authenticated session for each scenario.
    Account account(boolean samples) throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD,
                        "displayName", "Test User", "includeSamples", samples))))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("VERIFICATION_REQUEST_ACCEPTED"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        jdbc.update("UPDATE campus_users SET email_verified_at=CURRENT_TIMESTAMP WHERE email=?", email);
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf()).param("email", email).param("password", PASSWORD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email)).andReturn();
        return new Account(email, (MockHttpSession) result.getRequest().getSession(false));
    }
    // Build a valid request fixture with dates that remain valid when tests run later.
    Map<String, Object> draft() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("company", "Example Labs"); d.put("role", "Backend Intern"); d.put("location", "New York, NY");
        d.put("status", "Applied"); d.put("appliedDate", LocalDate.now().minusDays(1).toString());
        d.put("deadline", LocalDate.now().plusDays(8).toString()); d.put("followUpDate", LocalDate.now().plusDays(2).toString());
        d.put("notes", "Prepare Java API examples"); d.put("stages", List.of()); return d;
    }
    // Create a record through the secured endpoint and verify its version header.
    JsonNode create(Account account, Map<String, Object> d) throws Exception {
        return body(mvc.perform(post("/api/applications").session(account.session()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(d)))
                .andExpect(status().isCreated()).andExpect(header().exists("ETag")).andReturn());
    }
    // Parse JSON responses for assertions without depending on property order.
    JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }

    // Verify anonymous reads, tokenless writes, and signed-out access are rejected.
    @Test void authenticationAndCsrfProtectTheApi() throws Exception {
        mvc.perform(get("/api/applications")).andExpect(status().isUnauthorized());
        Account a = account(false);
        mvc.perform(post("/api/applications").session(a.session()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(draft()))).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").session(a.session()).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    // Exercise real token retrieval instead of the test-only CSRF helper.
    @Test void realCsrfEndpointTokenWorksForRegistrationAndLogin() throws Exception {
        MvcResult csrfResult = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode token = body(csrfResult);
        MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);
        String email = "real-csrf-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").session(session).header(token.get("headerName").asText(), token.get("token").asText())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email", email,
                        "displayName", "CSRF User", "password", PASSWORD, "includeSamples", false))))
                .andExpect(status().isAccepted());
        jdbc.update("UPDATE campus_users SET email_verified_at=CURRENT_TIMESTAMP WHERE email=?", email);
        mvc.perform(post("/api/auth/login").session(session).header(token.get("headerName").asText(), token.get("token").asText())
                .param("email", email).param("password", PASSWORD)).andExpect(status().isOk());
    }

    // Verify password hashing, rejected credentials, and duplicate email handling.
    @Test void registrationHashesPasswordsAndInvalidLoginsFail() throws Exception {
        Account a = account(false);
        var user = users.findByEmail(a.email()).orElseThrow();
        assertThat(user.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD, user.getPasswordHash())).isTrue();
        mvc.perform(post("/api/auth/login").with(csrf()).param("email", a.email()).param("password", "incorrect"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", a.email(), "password", PASSWORD,
                        "displayName", "Duplicate", "includeSamples", false))))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("VERIFICATION_REQUEST_ACCEPTED"));
        assertThat(users.findByEmail(a.email()).orElseThrow().getDisplayName()).isEqualTo("Test User");
    }

    // Verify each password requirement and confirm rejected requests persist no account.
    @Test void registrationRejectsWeakPasswordsWithoutCreatingAnAccount() throws Exception {
        for (String password : List.of("", "Abcdefghi1!", "abcdefghij1!", "ABCDEFGHIJ1!",
                "Abcdefghijk!", "Abcdefghijk1", "Abcdefghij1 ", "Ab1!" + "a".repeat(69),
                "Ab1!" + "é".repeat(35), "Ab1!" + "😀".repeat(7))) {
            String email = "rejected-" + UUID.randomUUID() + "@example.test";
            mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("email", email, "password", password,
                            "displayName", "Policy Tester", "includeSamples", true))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
            assertThat(users.existsByEmail(email)).isFalse();
        }
    }

    // Check Unicode and exact length boundaries, including successful login afterward.
    @Test void registrationAcceptsStrongPasswordsAtCharacterAndByteBoundaries() throws Exception {
        for (String password : List.of("Abcdefghij1!", "Ab1!" + "a".repeat(68),
                "Ab1!" + "é".repeat(34), "Ab1!" + "😀".repeat(8), "Àbcdefghij1!")) {
            String email = "accepted-" + UUID.randomUUID() + "@example.test";
            mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("email", email, "password", password,
                            "displayName", "Policy Tester", "includeSamples", false))))
                    .andExpect(status().isAccepted());
            jdbc.update("UPDATE campus_users SET email_verified_at=CURRENT_TIMESTAMP WHERE email=?", email);
            mvc.perform(post("/api/auth/login").with(csrf()).param("email", email).param("password", password))
                    .andExpect(status().isOk());
        }
    }

    // Ensure the new signup policy does not lock out older accounts.
    @Test void existingAccountsCanStillSignInWithTheirOriginalPassword() throws Exception {
        String email = "legacy-" + UUID.randomUUID() + "@example.test";
        users.saveAndFlush(new com.campus.tracker.auth.CampusUser(email, "Existing User", passwords.encode("oldpassword")));
        jdbc.update("UPDATE campus_users SET email_verification_exempt=TRUE WHERE email=?", email);
        mvc.perform(post("/api/auth/login").with(csrf()).param("email", email).param("password", "oldpassword"))
                .andExpect(status().isOk());
    }

    // Verify optional demo data belongs only to the requesting account.
    @Test void samplesAreOptionalAndIsolatedPerAccount() throws Exception {
        Account seeded = account(true), empty = account(false);
        mvc.perform(get("/api/applications").session(seeded.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(10)).andExpect(jsonPath("$.content[0].sample").value(true));
        mvc.perform(get("/api/applications").session(empty.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // Check persisted edits, stable creation timestamps, and optimistic version requirements.
    @Test void createReadUpdateAndVersionConflict() throws Exception {
        Account a = account(false); JsonNode initial = create(a, draft());
        String path = "/api/applications/" + initial.get("id").asText();
        mvc.perform(get(path).session(a.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.company").value("Example Labs")).andExpect(jsonPath("$.notes").value("Prepare Java API examples"));
        var changed = draft(); changed.put("company", "Updated Labs"); changed.put("status", "Interviewing");
        JsonNode updated = body(mvc.perform(put(path).session(a.session()).with(csrf())
                .header("If-Match", initial.get("version").asLong()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(changed))).andExpect(status().isOk()).andReturn());
        assertThat(updated.get("version").asLong()).isGreaterThan(initial.get("version").asLong());
        assertThat(updated.get("createdAt").asText()).isEqualTo(initial.get("createdAt").asText());
        mvc.perform(put(path).session(a.session()).with(csrf()).header("If-Match", initial.get("version").asLong())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(draft()))).andExpect(status().isConflict());
        mvc.perform(put(path).session(a.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(draft()))).andExpect(status().isPreconditionRequired());
    }

    // Attempt all record operations from another account to guard against ownership bypass.
    @Test void anotherUserCannotReadModifyDeleteOrRestoreRecords() throws Exception {
        Account owner = account(false), other = account(false);
        JsonNode app = create(owner, draft()); String path = "/api/applications/" + app.get("id").asText();
        mvc.perform(get(path).session(other.session())).andExpect(status().isNotFound());
        mvc.perform(put(path).session(other.session()).with(csrf()).header("If-Match", 0)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(draft()))).andExpect(status().isNotFound());
        mvc.perform(delete(path).session(other.session()).with(csrf()).header("If-Match", 0)).andExpect(status().isNotFound());
        mvc.perform(post(path + "/restore").session(other.session()).with(csrf()).header("If-Match", 0)).andExpect(status().isNotFound());
        mvc.perform(get("/api/applications").session(other.session()).param("q", "Example")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // Verify deletion hides records and undo requires the latest version.
    @Test void softDeleteAndUndoPreserveData() throws Exception {
        Account a = account(false); JsonNode initial = create(a, draft());
        String path = "/api/applications/" + initial.get("id").asText();
        JsonNode deleted = body(mvc.perform(delete(path).session(a.session()).with(csrf()).header("If-Match", 0))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(true)).andReturn());
        mvc.perform(get(path).session(a.session())).andExpect(status().isNotFound());
        mvc.perform(get("/api/applications").session(a.session())).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(post(path + "/restore").session(a.session()).with(csrf()).header("If-Match", 0)).andExpect(status().isConflict());
        mvc.perform(post(path + "/restore").session(a.session()).with(csrf()).header("If-Match", deleted.get("version").asLong()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(false)).andExpect(jsonPath("$.company").value("Example Labs"));
    }

    // Verify date validation and stage creation, updates, and removal.
    @Test void scheduledStagesValidatePersistUpdateAndRemove() throws Exception {
        Account a = account(false); var d = draft();
        d.put("stages", List.of(Map.of("name", "Technical", "state", "Scheduled")));
        mvc.perform(post("/api/applications").session(a.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(d))).andExpect(status().isBadRequest());
        d.put("stages", List.of(Map.of("name", "Technical", "state", "Scheduled", "date", LocalDate.now().plusDays(1).toString())));
        JsonNode created = create(a, d); String path = "/api/applications/" + created.get("id").asText();
        String stageId = created.get("stages").get(0).get("id").asText();
        d.put("stages", List.of(Map.of("id", stageId, "name", "Technical", "state", "Completed", "date", LocalDate.now().toString())));
        JsonNode updated = body(mvc.perform(put(path).session(a.session()).with(csrf()).header("If-Match", 0)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(d)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stages[0].state").value("Completed")).andReturn());
        assertThat(updated.get("stages").get(0).get("id").asText()).isEqualTo(stageId);
        d.put("stages", List.of());
        mvc.perform(put(path).session(a.session()).with(csrf()).header("If-Match", updated.get("version").asLong())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(d)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stages").isEmpty());
    }

    // Reject malformed data and attempted injection of server-owned fields.
    @Test void inputValidationRejectsInvalidDatesStatusAndOwnerInjection() throws Exception {
        Account a = account(false);
        for (Map<String, Object> invalid : List.of(Map.<String,Object>of("company", " "),
                Map.<String,Object>of("status", "Unknown"), Map.<String,Object>of("appliedDate", "2030-02-31"),
                Map.<String,Object>of("appliedDate", LocalDate.now().plusDays(1).toString()),
                Map.<String,Object>of("ownerId", UUID.randomUUID().toString()))) {
            var d = draft(); d.putAll(invalid);
            mvc.perform(post("/api/applications").session(a.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(d))).andExpect(status().isBadRequest());
        }
        var d = draft(); d.put("appliedDate", null);
        mvc.perform(post("/api/applications").session(a.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(d))).andExpect(status().isBadRequest());
        d.put("status", "Saved"); create(a, d);
    }

    // Verify bounded paging, literal wildcard searches, and an allowed sort-field list.
    @Test void paginationSearchAndStatusFiltersAreOwnerScoped() throws Exception {
        Account a = account(true);
        mvc.perform(get("/api/applications").session(a.session()).param("size", "3").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.totalElements").value(10)).andExpect(jsonPath("$.totalPages").value(4));
        mvc.perform(get("/api/applications").session(a.session()).param("q", "figma").param("status", "Interviewing"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/applications").session(a.session()).param("q", "%"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/applications").session(a.session()).param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/applications").session(a.session()).param("sort", "unsafe_column")).andExpect(status().isBadRequest());
    }
}
