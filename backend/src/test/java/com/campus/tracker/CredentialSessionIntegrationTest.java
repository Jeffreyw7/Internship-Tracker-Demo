package com.campus.tracker;

import com.campus.tracker.auth.CampusPrincipal;
import com.campus.tracker.auth.CampusUser;
import com.campus.tracker.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CredentialSessionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.security.web.FilterChainProxy chain;
    @MockitoSpyBean PasswordEncoder passwords;

    private CampusUser account() {
        var user = new CampusUser(UUID.randomUUID()+"@example.test", "Private Name", passwords.encode("Distinctive campus passphrase 847"));
        user.verifyEmail(Instant.now());
        return users.saveAndFlush(user);
    }
    private MockHttpSession session(CampusPrincipal principal) {
        var session = new MockHttpSession();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(authentication));
        return session;
    }
    @Test void legacyPrincipalCannotAccessProtectedApiOrDeleteNewerBrowserCookie() throws Exception {
        var user = account();
        var old = session(new CampusPrincipal(user.getId(), user.getDisplayName(), user.getEmail(), user.getPasswordHash(), true));
        mvc.perform(get("/api/auth/me").session(old)).andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(old.isInvalid()).isTrue();
    }
    @Test void oldEpochIsRejectedWhileDifferentAccountAndNewSnapshotRemainUsable() throws Exception {
        var first = account();
        var unrelated = account();
        var old = session(CampusPrincipal.from(first));
        mvc.perform(get("/api/auth/me").session(old)).andExpect(status().isOk());
        jdbc.update("UPDATE campus_users SET credential_version=credential_version+1 WHERE id=?", first.getId());
        mvc.perform(get("/api/auth/me").session(old)).andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get("/api/auth/me").session(session(CampusPrincipal.from(unrelated))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(unrelated.getId().toString()));
        mvc.perform(get("/api/auth/me").session(session(CampusPrincipal.from(users.findById(first.getId()).orElseThrow()))))
                .andExpect(status().isOk());
    }
    @Test void stalePrincipalIsDiscardedBeforePublicCsrfAndDoesNotExpireCookie() throws Exception {
        var user = account();
        var old = session(CampusPrincipal.from(user));
        jdbc.update("UPDATE campus_users SET credential_version=1 WHERE id=?", user.getId());
        mvc.perform(get("/api/auth/csrf").session(old)).andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString()).andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(old.isInvalid()).isTrue();
    }
    @Test void deletedAccountSnapshotCannotAccessProtectedApi() throws Exception {
        var missing = new CampusUser("missing@example.test", "Missing", "private-hash");
        missing.verifyEmail(Instant.now());
        mvc.perform(get("/api/auth/me").session(session(CampusPrincipal.from(missing))))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));
    }
    @Test void loginRejectsPasswordSnapshotChangedDuringAuthentication() throws Exception {
        var user = account();
        doAnswer(invocation -> {
            boolean matched = (boolean) invocation.callRealMethod();
            if (matched) jdbc.update("UPDATE campus_users SET credential_version=credential_version+1 WHERE id=?", user.getId());
            return matched;
        }).when(passwords).matches(eq("Distinctive campus passphrase 847"), eq(user.getPasswordHash()));
        try {
            var result = mvc.perform(post("/api/auth/login").with(csrf()).param("email",user.getEmail())
                    .param("password","Distinctive campus passphrase 847"))
                    .andExpect(status().isUnauthorized()).andReturn();
            assertThat(result.getRequest().getSession(false)).isNull();
        } finally { reset(passwords); }
    }
    @Test void authenticationShellAndApiHaveRecoverySecurityHeaders() throws Exception {
        mvc.perform(get("/")).andExpect(header().string("Referrer-Policy","no-referrer"))
                .andExpect(header().string("Content-Security-Policy",org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("frame-ancestors 'none'"),
                        org.hamcrest.Matchers.containsString("form-action 'self'"))))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(get("/api/auth/csrf")).andExpect(header().string("Referrer-Policy","no-referrer"))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));
    }
    @Test void versionChecksPrecedeAbuseCsrfAndResetBodyParsing() {
        var types = chain.getFilters("/api/auth/password-reset/confirm").stream().map(filter -> filter.getClass().getSimpleName()).toList();
        assertThat(types.indexOf("CredentialVersionFilter")).isGreaterThan(types.indexOf("HeaderWriterFilter"));
        assertThat(types.indexOf("CredentialVersionFilter")).isLessThan(types.indexOf("AuthAbuseFilter"));
        assertThat(types.indexOf("AuthAbuseFilter")).isLessThan(types.indexOf("CsrfFilter"));
        assertThat(types.indexOf("ResetInputFilter")).isGreaterThan(types.indexOf("CsrfFilter"));
        assertThat(types.indexOf("ResetInputFilter")).isLessThan(types.indexOf("LoginWorkFilter"));
    }
}
