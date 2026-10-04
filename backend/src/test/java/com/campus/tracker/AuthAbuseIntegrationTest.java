package com.campus.tracker;

import com.campus.tracker.auth.UserRepository;
import com.campus.tracker.auth.abuse.AuthWorkLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "campus.auth-abuse.login-ip.capacity=2", "campus.auth-abuse.login-ip.refill-period=1h",
    "campus.auth-abuse.login-email.capacity=2", "campus.auth-abuse.login-email.refill-period=1h",
    "campus.auth-abuse.registration-ip.capacity=2", "campus.auth-abuse.registration-ip.refill-period=1h",
    "campus.auth-abuse.csrf-ip.capacity=4", "campus.auth-abuse.csrf-ip.refill-period=1h",
    "campus.auth-abuse.maximum-active=1"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthAbuseIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthWorkLimiter work;
    @Autowired UserRepository users;
    @MockitoSpyBean PasswordEncoder passwords;

    MockHttpServletRequestBuilder login(String ip, String email) {
        return post("/api/auth/login").with(csrf())
            .with(r -> { r.setRemoteAddr(ip); return r; }).param("email", email).param("password", "incorrect");
    }
    MockHttpServletRequestBuilder register(String ip, String email) {
        return post("/api/auth/register").with(csrf()).with(r -> { r.setRemoteAddr(ip); return r; })
            .contentType(MediaType.APPLICATION_JSON).content("""
            {"displayName":"Test","email":"%s","password":"Campus-test-123!","includeSamples":false}
            """.formatted(email));
    }
    @Test void ipLimitStopsRotatingEmailsAndIgnoresForwardedHeaders() throws Exception {
        for (int i = 0; i < 2; i++) mvc.perform(login("192.0.2.11", "rotate"+i+"@example.test")).andExpect(status().isUnauthorized());
        clearInvocations(passwords);
        mvc.perform(login("192.0.2.11", "other@example.test").header("X-Forwarded-For", "192.0.2.200")
            .header("Forwarded", "for=192.0.2.201")).andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After")).andExpect(jsonPath("$.message").value("Too many authentication requests. Please try again shortly."));
        verifyNoInteractions(passwords);
    }
    @Test void identityLimitSpansIpsAndCanonicalizesEmail() throws Exception {
        mvc.perform(login("192.0.2.21", "Identity@example.test")).andExpect(status().isUnauthorized());
        mvc.perform(login("192.0.2.22", " identity@EXAMPLE.test ")).andExpect(status().isUnauthorized());
        clearInvocations(passwords);
        mvc.perform(login("192.0.2.23", "identity@example.test")).andExpect(status().isTooManyRequests());
        verifyNoInteractions(passwords);
    }
    @Test void registrationThrottlingCreatesNoExtraAccounts() throws Exception {
        mvc.perform(register("192.0.2.31", "first-"+UUID.randomUUID()+"@example.test")).andExpect(status().isAccepted());
        mvc.perform(register("192.0.2.31", "second-"+UUID.randomUUID()+"@example.test")).andExpect(status().isAccepted());
        long count = users.count();
        mvc.perform(register("192.0.2.31", "blocked-"+UUID.randomUUID()+"@example.test")).andExpect(status().isTooManyRequests());
        assertThat(users.count()).isEqualTo(count);
    }
    @Test void csrfHeadAndGetShareBudgetAndRejectionCreatesNoSession() throws Exception {
        for (int i = 0; i < 4; i++) mvc.perform(get("/api/auth/csrf").with(r -> {r.setRemoteAddr("192.0.2.41"); return r;})).andExpect(status().isOk());
        var result = mvc.perform(head("/api/auth/csrf").with(r -> {r.setRemoteAddr("192.0.2.41"); return r;}))
            .andExpect(status().isTooManyRequests()).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
    }
    @Test void workSaturationBlocksLoginAndRegistrationThenRecovers() throws Exception {
        long count = users.count();
        try (var permit = work.tryAcquire()) {
            assertThat(permit).isNotNull();
            clearInvocations(passwords);
            mvc.perform(login("192.0.2.51", "busy@example.test")).andExpect(status().isTooManyRequests());
            mvc.perform(register("192.0.2.52", "busy-registration@example.test")).andExpect(status().isTooManyRequests());
            verifyNoInteractions(passwords);
            assertThat(users.count()).isEqualTo(count);
        }
        mvc.perform(login("192.0.2.51", "busy@example.test")).andExpect(status().isUnauthorized());
        mvc.perform(register("192.0.2.52", "recovered-"+UUID.randomUUID()+"@example.test")).andExpect(status().isAccepted());
    }
    @Test void csrfStillRequiredAndInvalidTrafficConsumesIpBudget() throws Exception {
        for (int i = 0; i < 2; i++) mvc.perform(post("/api/auth/login").with(r -> {r.setRemoteAddr("192.0.2.61"); return r;}))
            .andExpect(status().isForbidden());
        mvc.perform(login("192.0.2.61", "csrf@example.test")).andExpect(status().isTooManyRequests());
    }
    @Test void ambiguousAndOversizedIdentitiesDoNotReachAuthentication() throws Exception {
        clearInvocations(passwords);
        mvc.perform(login("192.0.2.71", "a@example.test").param("email","b@example.test")).andExpect(status().isBadRequest());
        mvc.perform(login("192.0.2.72", "x".repeat(255))).andExpect(status().isBadRequest());
        verifyNoInteractions(passwords);
    }
    @Test void rotatingUnsafePathsCannotAllocateUnlimitedAnonymousSessions() throws Exception {
        String[] paths = {"/api/does-not-exist", "/api/auth/csrf", "/api/auth/logout", "/unmapped"};
        for (String path : paths) {
            var result = mvc.perform(post(path).with(r -> {r.setRemoteAddr("192.0.2.91"); return r;}))
                .andExpect(status().isForbidden()).andReturn();
            assertThat(result.getRequest().getSession(false)).isNotNull();
        }
        var rejected = mvc.perform(put("/api/auth/login").with(r -> {r.setRemoteAddr("192.0.2.91"); return r;}))
            .andExpect(status().isTooManyRequests()).andReturn();
        assertThat(rejected.getRequest().getSession(false)).isNull();
    }
    @Test void contextPathDoesNotBypassLoginBudget() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/campus/api/auth/login").contextPath("/campus").servletPath("/api/auth/login")
                .with(csrf()).with(r -> {r.setRemoteAddr("192.0.2.92"); return r;})
                .param("email", "context"+i+"@example.test").param("password","incorrect"))
                .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/campus/api/auth/login").contextPath("/campus").servletPath("/api/auth/login")
            .with(csrf()).with(r -> {r.setRemoteAddr("192.0.2.92"); return r;})
            .param("email","context-other@example.test").param("password","incorrect"))
            .andExpect(status().isTooManyRequests());
    }

    @Test void registrationWithRealCsrfCanLoginAndRefreshToken() throws Exception {
        String email = "flow-"+UUID.randomUUID()+"@example.test";
        var first = mvc.perform(get("/api/auth/csrf").with(r -> {r.setRemoteAddr("192.0.2.81"); return r;})).andReturn();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var token = json.readTree(first.getResponse().getContentAsString());
        var session = (org.springframework.mock.web.MockHttpSession)first.getRequest().getSession(false);
        mvc.perform(post("/api/auth/register").session(session).with(r -> {r.setRemoteAddr("192.0.2.81"); return r;}).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("displayName","Flow","email",email,"password","Campus-test-123!","includeSamples",false))).header(token.get("headerName").asText(),token.get("token").asText())).andExpect(status().isAccepted());
        mvc.perform(post("/api/auth/login").session(session).with(csrf())
            .with(r -> {r.setRemoteAddr("192.0.2.82"); return r;})
            .param("email",email).param("password","Campus-test-123!"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_REQUIRED"));
        // The verification suite tests token confirmation; this fixture isolates login/work limiting.
        var verified = users.findByEmail(email).orElseThrow();
        verified.verifyEmail(java.time.Instant.now());
        users.saveAndFlush(verified);
        var signedIn = mvc.perform(post("/api/auth/login").session(session)
            .with(r -> {r.setRemoteAddr("192.0.2.81"); return r;})
            .header(token.get("headerName").asText(),token.get("token").asText())
            .param("email",email).param("password","Campus-test-123!")).andExpect(status().isOk()).andReturn();
        var authenticated = (org.springframework.mock.web.MockHttpSession)signedIn.getRequest().getSession(false);
        mvc.perform(get("/api/auth/csrf").session(authenticated).with(r -> {r.setRemoteAddr("192.0.2.81"); return r;})).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").session(authenticated).with(csrf())).andExpect(status().isNoContent());
    }
}
